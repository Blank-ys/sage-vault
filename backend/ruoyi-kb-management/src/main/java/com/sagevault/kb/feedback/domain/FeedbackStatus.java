package com.sagevault.kb.feedback.domain;

/**
 * 反馈的管理员处理状态。
 *
 * <p>用户提交后固定进入 {@link #PENDING}；是否流转到 {@link #RESOLVED} 由管理端决定，
 * 用户侧不能修改该状态。
 */
public enum FeedbackStatus {
    /** 待处理。 */
    PENDING,
    /** 已处理。 */
    RESOLVED
}
