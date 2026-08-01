package com.sagevault.kb.conversation.mapper;

import com.sagevault.kb.conversation.domain.ConversationEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ConversationMapper {
    int insert(ConversationEntity entity);
    ConversationEntity findById(long id);
    List<ConversationEntity> findByUser(@Param("userId") long userId);
    int updateTitle(@Param("id") long id, @Param("userId") long userId, @Param("title") String title);
    int touch(@Param("id") long id);
    int deleteOwned(@Param("id") long id, @Param("userId") long userId);
}
