package com.sagevault.kb.qarecord.domain;

import java.time.LocalDateTime;

/**
 * 问答历史条目。
 *
 * <p>{@code feedbackSubmitted} 只表达"是否已提交过反馈"，用于前端把入口收敛为已提交状态，
 * 不携带反馈正文。
 */
public record QaRecordResponse(long id, long conversationId, String generationId, String question,
        String answer, QaRecordStatus status, LocalDateTime createdAt, boolean feedbackSubmitted) {

    public QaRecordResponse withFeedbackSubmitted(boolean submitted) {
        return new QaRecordResponse(id, conversationId, generationId, question, answer, status,
                createdAt, submitted);
    }
}
