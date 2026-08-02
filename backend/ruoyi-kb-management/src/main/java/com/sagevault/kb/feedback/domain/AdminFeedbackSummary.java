package com.sagevault.kb.feedback.domain;

import java.time.LocalDateTime;

/**
 * 管理端反馈列表项。
 *
 * <p>列表用于排队和筛选，只暴露反馈自身的元数据，不携带问题与答案正文。
 * 正文属于用户授权共享的内容，仅在管理员显式打开某条反馈详情时才返回。
 */
public record AdminFeedbackSummary(
        Long id,
        Long qaId,
        FeedbackCategory category,
        String comment,
        FeedbackStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminFeedbackSummary from(FeedbackEntity entity) {
        return new AdminFeedbackSummary(
                entity.getId(),
                entity.getQaId(),
                entity.getCategory(),
                entity.getComment(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
