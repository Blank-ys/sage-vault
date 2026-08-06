package com.sagevault.kb.conversation.domain;

import com.sagevault.kb.qarecord.domain.RetrievedChunkDiagnostic;

public sealed interface AnswerEvent
        permits AnswerEvent.Started, AnswerEvent.Delta, AnswerEvent.Completed, AnswerEvent.Refused,
                AnswerEvent.Stopped, AnswerEvent.Failed {
    String generationId();

    record Started(String generationId) implements AnswerEvent { }
    record Delta(String generationId, String delta) implements AnswerEvent { }
    record Completed(
            String generationId,
            java.util.List<RetrievedChunkDiagnostic> retrievalDiagnostics,
            java.util.Map<String, Integer> stageDurations)
            implements AnswerEvent { }
    record Refused(String generationId, String message) implements AnswerEvent { }

    /** 生成被用户显式停止；此前已下发的 delta 依然有效。 */
    record Stopped(String generationId) implements AnswerEvent { }

    /** 生成已经开始，但 RAG 管线运行时失败，无法完成。 */
    record Failed(String generationId, String detail) implements AnswerEvent { }
}
