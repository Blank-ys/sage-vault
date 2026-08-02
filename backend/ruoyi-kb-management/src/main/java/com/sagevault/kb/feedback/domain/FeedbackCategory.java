package com.sagevault.kb.feedback.domain;

/**
 * 用户提交反馈时选择的类别。
 *
 * <p>类别是封闭集合，用户不能自定义；未知类别一律拒绝，避免管理端出现无法归类的反馈。
 */
public enum FeedbackCategory {
    /** 答案错误。 */
    WRONG_ANSWER,
    /** 没有找到答案。 */
    NO_ANSWER_FOUND,
    /** 答案不完整。 */
    INCOMPLETE_ANSWER,
    /** 其他问题。 */
    OTHER
}
