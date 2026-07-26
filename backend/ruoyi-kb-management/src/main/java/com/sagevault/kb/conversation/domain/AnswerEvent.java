package com.sagevault.kb.conversation.domain;

public sealed interface AnswerEvent permits AnswerEvent.Started, AnswerEvent.Refused {
    String generationId();

    record Started(String generationId) implements AnswerEvent { }
    record Refused(String generationId, String message) implements AnswerEvent { }
}
