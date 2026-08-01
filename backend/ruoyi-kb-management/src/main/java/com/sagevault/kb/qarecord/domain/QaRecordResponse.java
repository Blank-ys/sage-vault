package com.sagevault.kb.qarecord.domain;

import java.time.LocalDateTime;

public record QaRecordResponse(long id, long conversationId, String generationId, String question,
        String answer, QaRecordStatus status, LocalDateTime createdAt) { }
