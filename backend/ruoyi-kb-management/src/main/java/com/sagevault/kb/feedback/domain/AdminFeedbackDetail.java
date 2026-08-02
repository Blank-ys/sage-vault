package com.sagevault.kb.feedback.domain;

import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import java.time.LocalDateTime;
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
 * <p>{@code retrievalDiagnostics} 与 {@code stageDurations} 是检索诊断占位：
 * 检索片段标识/分数与阶段耗时的采集链路由 11c 建立（Python → 契约 → Java），
 * 在其落地前这两个字段恒为空，管理端据此提示诊断信息尚未接入。
 * 保留字段是为了让 11c 只需填充数据，无需再改管理端 API 形状。
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
}
