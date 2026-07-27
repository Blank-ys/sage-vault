package com.sagevault.kb.document.domain;

import java.time.LocalDateTime;

public record DocumentResponse(long id, long knowledgeBaseId, String filename, String normalizedName,
        DocumentStatus status, long size, String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
