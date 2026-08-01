package com.sagevault.kb.conversation.domain;

public sealed interface AnswerEvent
        permits AnswerEvent.Started, AnswerEvent.Delta, AnswerEvent.Completed, AnswerEvent.Refused,
                AnswerEvent.Stopped {
    String generationId();

    record Started(String generationId) implements AnswerEvent { }
    record Delta(String generationId, String delta) implements AnswerEvent { }
    record Completed(String generationId) implements AnswerEvent { }
    record Refused(String generationId, String message) implements AnswerEvent { }

    /** 生成被用户显式停止；此前已下发的 delta 依然有效。 */
    record Stopped(String generationId) implements AnswerEvent { }
}
