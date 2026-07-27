package com.sagevault.kb.qarecord.service;

public interface QaRecordService {
    void create(long conversationId, long userId, String requestId, String generationId, String question);
    void markRefused(String generationId, String answer);
    void markUnfinished(String generationId);
}
