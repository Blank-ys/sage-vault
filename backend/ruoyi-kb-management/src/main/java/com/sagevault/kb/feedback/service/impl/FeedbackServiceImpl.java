package com.sagevault.kb.feedback.service.impl;

import com.sagevault.kb.feedback.domain.AdminFeedbackDetail;
import com.sagevault.kb.feedback.domain.AdminFeedbackDetailRow;
import com.sagevault.kb.feedback.domain.AdminFeedbackPage;
import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.AdminFeedbackSummary;
import com.sagevault.kb.feedback.domain.FeedbackCategory;
import com.sagevault.kb.feedback.domain.FeedbackEntity;
import com.sagevault.kb.feedback.domain.FeedbackResponse;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import com.sagevault.kb.feedback.domain.ResolveFeedbackRequest;
import com.sagevault.kb.feedback.domain.SubmitFeedbackRequest;
import com.sagevault.kb.feedback.mapper.FeedbackMapper;
import com.sagevault.kb.feedback.service.FeedbackService;
import com.sagevault.kb.feedback.service.port.FeedbackAudit;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackServiceImpl implements FeedbackService {
    private static final int COMMENT_MAX_LENGTH = 1000;

    private static final int ADMIN_NOTE_MAX_LENGTH = 1000;

    private final FeedbackMapper feedbacks;
    private final QaRecordMapper qaRecords;
    private final FeedbackAudit audit;

    public FeedbackServiceImpl(
            FeedbackMapper feedbacks, QaRecordMapper qaRecords, FeedbackAudit audit) {
        this.feedbacks = feedbacks;
        this.qaRecords = qaRecords;
        this.audit = audit;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackResponse submit(long userId, long qaId, SubmitFeedbackRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "反馈请求不能为空");
        }
        QaRecordEntity record = requireOwnedRecord(userId, qaId);
        requireAnswered(record);
        requireConsent(request);

        FeedbackEntity entity = new FeedbackEntity();
        entity.setQaId(qaId);
        entity.setUserId(userId);
        entity.setCategory(parseCategory(request.category()));
        entity.setComment(normalizeComment(request.comment()));
        entity.setStatus(FeedbackStatus.PENDING);
        entity.setAdminNote("");

        // 同一条问答只允许一条反馈：先查后插仍可能并发重入，由唯一键在库层兜底。
        if (feedbacks.findByQaId(qaId) != null) {
            throw new BusinessException(ErrorCode.FEEDBACK_ALREADY_SUBMITTED, "该问答已提交过反馈");
        }
        try {
            feedbacks.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.FEEDBACK_ALREADY_SUBMITTED, "该问答已提交过反馈", e);
        }
        return FeedbackResponse.from(feedbacks.findByQaId(qaId));
    }

    @Override
    public Map<Long, FeedbackResponse> findSubmitted(List<Long> qaIds) {
        if (qaIds == null || qaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FeedbackResponse> submitted = new LinkedHashMap<>();
        for (FeedbackEntity entity : feedbacks.findByQaIds(qaIds)) {
            submitted.put(entity.getQaId(), FeedbackResponse.from(entity));
        }
        return submitted;
    }

    @Override
    public AdminFeedbackPage listForAdmin(AdminFeedbackQuery query) {
        AdminFeedbackQuery effective =
                query == null ? AdminFeedbackQuery.of(null, null, null) : query;
        long total = feedbacks.countForAdmin(effective);
        if (total == 0) {
            return new AdminFeedbackPage(
                    List.of(), 0, effective.pageNum(), effective.pageSize());
        }
        List<AdminFeedbackSummary> items =
                feedbacks.findForAdmin(effective).stream().map(AdminFeedbackSummary::from).toList();
        return new AdminFeedbackPage(items, total, effective.pageNum(), effective.pageSize());
    }

    @Override
    public AdminFeedbackDetail findDetailForAdmin(long adminUserId, long feedbackId) {
        AdminFeedbackDetail detail = AdminFeedbackDetail.from(requireDetail(feedbackId));
        // 审计是远程调用，放在事务外；查看行为本身不改状态，无需事务。
        audit.recordViewed(detail.id(), detail.qaId());
        return detail;
    }

    @Override
    public AdminFeedbackDetail resolve(
            long adminUserId, long feedbackId, ResolveFeedbackRequest request) {
        if (request == null || request.status() == null) {
            throw new BusinessException(ErrorCode.FEEDBACK_STATUS_INVALID, "处理状态不能为空");
        }
        String note = normalizeAdminNote(request.adminNote());
        // 单条 UPDATE 自带原子性，无需额外事务；审计是远程调用，必须留在事务外。
        if (feedbacks.updateStatus(feedbackId, request.status(), note) == 0) {
            audit.recordResolveFailed(feedbackId, "反馈不存在");
            throw new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND, "反馈不存在");
        }
        audit.recordResolved(feedbackId, request.status().name());
        return AdminFeedbackDetail.from(requireDetail(feedbackId));
    }

    private AdminFeedbackDetailRow requireDetail(long feedbackId) {
        AdminFeedbackDetailRow row = feedbacks.findDetailForAdmin(feedbackId);
        if (row == null) {
            throw new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND, "反馈不存在");
        }
        return row;
    }

    private static String normalizeAdminNote(String adminNote) {
        if (adminNote == null) {
            return "";
        }
        String trimmed = adminNote.trim();
        if (trimmed.length() > ADMIN_NOTE_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "内部备注长度超过限制");
        }
        return trimmed;
    }

    /**
     * 反馈只能由问答的归属者提交。
     *
     * <p>问答不存在与不属于当前用户返回同一个错误，避免通过错误码探测他人问答是否存在。
     */
    private QaRecordEntity requireOwnedRecord(long userId, long qaId) {
        QaRecordEntity record = qaRecords.findById(qaId);
        if (record == null || record.getUserId() == null || record.getUserId() != userId) {
            throw new BusinessException(ErrorCode.FEEDBACK_FORBIDDEN, "无权对该问答提交反馈");
        }
        return record;
    }

    /** 生成中的问答还没有最终答案，此时反馈内容无法对应稳定的问答快照。 */
    private static void requireAnswered(QaRecordEntity record) {
        if (record.getStatus() == QaRecordStatus.STARTED) {
            throw new BusinessException(ErrorCode.FEEDBACK_ANSWER_NOT_READY, "回答尚未结束，暂不能提交反馈");
        }
    }

    /** 反馈会把问题与答案共享给管理员，必须拿到用户的明确同意才写入。 */
    private static void requireConsent(SubmitFeedbackRequest request) {
        if (!Boolean.TRUE.equals(request.consentToShare())) {
            throw new BusinessException(ErrorCode.FEEDBACK_CONSENT_REQUIRED, "需要同意共享问答内容后才能提交反馈");
        }
    }

    private static FeedbackCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new BusinessException(ErrorCode.FEEDBACK_CATEGORY_INVALID, "反馈类别不能为空");
        }
        try {
            return FeedbackCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.FEEDBACK_CATEGORY_INVALID, "反馈类别不合法", e);
        }
    }

    private static String normalizeComment(String comment) {
        if (comment == null) {
            return "";
        }
        String trimmed = comment.trim();
        if (trimmed.length() > COMMENT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.FEEDBACK_COMMENT_TOO_LONG, "反馈说明长度超过限制");
        }
        return trimmed;
    }
}
