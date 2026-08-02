package com.sagevault.kb.conversation.domain;

import java.time.LocalDateTime;

/**
 * @param knowledgeBaseDeleted 知识库活动记录已被级联删除移除；历史仍可读，但不能继续提问
 * @param knowledgeBaseName 会话所属知识库名称，知识库已删除时为空
 */
public record ConversationResponse(long id, long userId, long knowledgeBaseId, String title,
        LocalDateTime createdAt, LocalDateTime updatedAt, boolean knowledgeBaseDeleted,
        String knowledgeBaseName) { }
