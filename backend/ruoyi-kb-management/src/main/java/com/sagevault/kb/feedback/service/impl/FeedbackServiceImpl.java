package com.sagevault.kb.feedback.service.impl;

import com.sagevault.kb.feedback.domain.FeedbackCategory;
import com.sagevault.kb.feedback.domain.FeedbackEntity;
import com.sagevault.kb.feedback.domain.FeedbackResponse;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import com.sagevault.kb.feedback.domain.SubmitFeedbackRequest;
import com.sagevault.kb.feedback.mapper.FeedbackMapper;
import com.sagevault.kb.feedback.service.FeedbackService;
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

    private final FeedbackMapper feedbacks;
    private final QaRecordMapper qaRecords;

    public FeedbackServiceImpl(FeedbackMapper feedbacks, QaRecordMapper qaRecords) {
        this.feedbacks = feedbacks;
        this.qaRecords = qaRecords;
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
