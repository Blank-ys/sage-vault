package com.sagevault.kb.feedback.domain;

import java.time.LocalDateTime;

/**
 * 返回给用户的反馈视图。
 *
 * <p>只暴露用户自己提交的内容与提交事实，不包含 {@code adminNote} 等管理端内部字段。
 */
public record FeedbackResponse(
        Long id, Long qaId, FeedbackCategory category, String comment, LocalDateTime createdAt) {

    public static FeedbackResponse from(FeedbackEntity entity) {
        return new FeedbackResponse(
                entity.getId(),
                entity.getQaId(),
                entity.getCategory(),
                entity.getComment(),
                entity.getCreatedAt());
    }
}
