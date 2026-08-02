package com.sagevault.kb.feedback.domain;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员处理反馈的请求体。
 *
 * <p>{@code adminNote} 是内部备注，只在管理端可见，不回流给提交反馈的用户。
 */
public record ResolveFeedbackRequest(
        @NotNull(message = "处理状态不能为空") FeedbackStatus status,
        @Size(max = 1000, message = "内部备注长度不能超过 1000 个字符") String adminNote) {}
