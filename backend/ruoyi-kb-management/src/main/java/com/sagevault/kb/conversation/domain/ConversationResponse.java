package com.sagevault.kb.conversation.domain;

import java.time.LocalDateTime;

public record ConversationResponse(long id, long userId, long knowledgeBaseId, String title,
        LocalDateTime createdAt, LocalDateTime updatedAt) { }
