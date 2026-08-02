package com.sagevault.kb.feedback.service.port;

/**
 * 管理员对反馈的操作审计。
 *
 * <p>审计只记录操作者与反馈标识，不写入问题、答案或反馈说明正文，
 * 避免用户内容通过操作日志二次扩散。
 */
public interface FeedbackAudit {

    /** 管理员查看了某条反馈的问答正文。 */
    void recordViewed(long feedbackId, long qaId);

    /** 管理员流转了某条反馈的处理状态。 */
    void recordResolved(long feedbackId, String status);
}
