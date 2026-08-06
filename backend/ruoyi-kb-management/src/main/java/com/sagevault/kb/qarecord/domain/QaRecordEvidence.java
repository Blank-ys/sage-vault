package com.sagevault.kb.qarecord.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次问答面向跨能力读取的证据快照。
 *
 * <p>包含问答正文、归属者与终态，以及检索/生成阶段诊断；诊断只含片段标识与分数，
 * 不含片段正文。问题、回答正文只有在调用方已取得授权（例如已存在对应反馈行）时才允许读取。
 */
public record QaRecordEvidence(
        long qaId,
        long userId,
        String requestId,
        String question,
        String answer,
        QaRecordStatus answerStatus,
        List<RetrievedChunkDiagnostic> retrievalDiagnostics,
        Map<String, Long> stageDurations) {}
