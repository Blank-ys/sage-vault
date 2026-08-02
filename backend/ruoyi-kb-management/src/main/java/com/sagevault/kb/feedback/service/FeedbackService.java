package com.sagevault.kb.feedback.service;

import com.sagevault.kb.feedback.domain.AdminFeedbackDetail;
import com.sagevault.kb.feedback.domain.AdminFeedbackPage;
import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.FeedbackResponse;
import com.sagevault.kb.feedback.domain.ResolveFeedbackRequest;
import com.sagevault.kb.feedback.domain.SubmitFeedbackRequest;
import java.util.List;
import java.util.Map;

public interface FeedbackService {
    /**
     * 用户对自己的一条问答提交反馈。
     *
     * <p>只有问答归属者本人、且明确同意共享问答内容时才允许写入；同一条问答只接受一次提交。
     */
    FeedbackResponse submit(long userId, long qaId, SubmitFeedbackRequest request);

    /** 批量查询这些问答是否已提交过反馈，用于历史列表回显。 */
    Map<Long, FeedbackResponse> findSubmitted(List<Long> qaIds);

    /**
     * 管理端反馈队列分页查询。
     *
     * <p>只返回反馈元数据，不含问答正文。
     */
    AdminFeedbackPage listForAdmin(AdminFeedbackQuery query);

    /**
     * 管理端反馈详情，包含用户已授权共享的问题与答案正文。
     *
     * @param adminUserId 操作管理员，用于记录查看审计
     */
    AdminFeedbackDetail findDetailForAdmin(long adminUserId, long feedbackId);

    /** 管理员流转处理状态并写入内部备注。 */
    AdminFeedbackDetail resolve(long adminUserId, long feedbackId, ResolveFeedbackRequest request);
}
