package com.sagevault.kb.conversation.mapper;

import com.sagevault.kb.conversation.domain.ConversationEntity;

public interface ConversationMapper {
    int insert(ConversationEntity entity);
    ConversationEntity findById(long id);
}
