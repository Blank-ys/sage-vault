package com.sagevault.kb.feedback.domain;

/**
 * 用户提交反馈的请求体。
 *
 * @param category 反馈类别，必填
 * @param comment 用户补充说明，可空
 * @param consentToShare 用户是否明确同意把问题与答案共享给管理员；未同意则不落库
 */
public record SubmitFeedbackRequest(String category, String comment, Boolean consentToShare) {}
