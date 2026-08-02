package com.sagevault.kb.feedback.service;

import com.sagevault.kb.feedback.domain.FeedbackResponse;
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
}
