package com.sagevault.kb.feedback.domain;

import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.domain.RetrievalDiagnosticEntity;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端反馈详情。
 *
 * <p>问题与答案正文只因为用户提交过反馈才出现在这里；未提交反馈的问答不会构造出该视图。
 *
 * <p>{@code answer} 可能是残缺回答（用户中途停止或生成失败），管理员需要据此排查，
 * 因此按问答记录的实际状态原样返回，不做补全或掩码。
 *
 * <p>{@code retrievalDiagnostics} 与 {@code stageDurations} 由 11c 贯通：
 * 检索片段标识/分数与阶段耗时的采集链路由 completed 事件携带，经 RAG 适配器解析后
 * 落库 {@code sv_qa_retrieval_diagnostic} 子表，最终在此装配。数据缺失时留空集合。
 */
public record AdminFeedbackDetail(
        Long id,
        Long qaId,
        FeedbackCategory category,
        String comment,
        FeedbackStatus status,
        String adminNote,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String requestId,
        String question,
        String answer,
        QaRecordStatus answerStatus,
        List<RetrievedChunkDiagnostic> retrievalDiagnostics,
        Map<String, Long> stageDurations) {

    public static AdminFeedbackDetail from(AdminFeedbackDetailRow row) {
        return new AdminFeedbackDetail(
                row.getId(),
                row.getQaId(),
                row.getCategory(),
                row.getComment(),
                row.getStatus(),
                row.getAdminNote(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getRequestId(),
                row.getQuestion(),
                row.getAnswer(),
                row.getAnswerStatus(),
                List.of(),
                Map.of());
    }

    public static AdminFeedbackDetail from(
            AdminFeedbackDetailRow row, List<RetrievalDiagnosticEntity> diagnostics) {
        List<RetrievedChunkDiagnostic> chunks = new java.util.ArrayList<>();
        Map<String, Long> stages = new LinkedHashMap<>();
        if (diagnostics != null) {
            for (RetrievalDiagnosticEntity entity : diagnostics) {
                if ("retrieval".equals(entity.getStage())) {
                    chunks.add(new RetrievedChunkDiagnostic(
                            entity.getDocumentId(), entity.getChunkId(), entity.getScore()));
                } else if (entity.getStage() != null && entity.getDurationMs() != null) {
                    stages.put(entity.getStage(), entity.getDurationMs());
                }
            }
        }
        return new AdminFeedbackDetail(
                row.getId(),
                row.getQaId(),
                row.getCategory(),
                row.getComment(),
                row.getStatus(),
                row.getAdminNote(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getRequestId(),
                row.getQuestion(),
                row.getAnswer(),
                row.getAnswerStatus(),
                chunks,
                stages);
    }
}
