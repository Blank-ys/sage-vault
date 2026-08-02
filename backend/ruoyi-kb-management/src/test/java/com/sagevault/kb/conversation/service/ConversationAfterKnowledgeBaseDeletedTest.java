package com.sagevault.kb.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner.CleanupProgress;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.support.InMemoryRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 知识库被级联删除后的历史可读性：会话与问答记录必须保留且被标记为"知识库已删除"，
 * 同时不能在该会话上继续提问。
 */
class ConversationAfterKnowledgeBaseDeletedTest {
    private static final long USER_ID = 7L;

    private InMemoryRepositories repositories;
    private ConversationService conversations;
    private KnowledgeBaseService knowledgeBases;
    private long conversationId;
    private long knowledgeBaseId;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        conversations = repositories.conversations();
        knowledgeBases = repositories.knowledgeBases();
        knowledgeBaseId = knowledgeBases.create(new CreateKnowledgeBaseRequest("产品手册", "历史可读性验证")).id();
        conversationId = conversations.create(USER_ID, new CreateConversationRequest(knowledgeBaseId)).id();
    }

    /** 让知识库走完一次成功的级联删除，活动记录被移除。 */
    private void completeCascadeDelete() {
        knowledgeBases.delete(knowledgeBaseId);
        repositories.cascadeDeleteTask(id -> CleanupProgress.completed()).advanceCascadeDeletes();
    }

    @Test
    void historicalConversationsRemainReadableAndAreMarkedAsDeleted() {
        completeCascadeDelete();

        ConversationResponse conversation = conversations.get(USER_ID, conversationId);
        assertThat(conversation.id()).isEqualTo(conversationId);
        assertThat(conversation.knowledgeBaseDeleted()).isTrue();
        assertThat(conversations.list(USER_ID)).extracting(ConversationResponse::id).contains(conversationId);
        assertThat(conversations.history(USER_ID, conversationId)).isNotNull();
    }

    @Test
    void askingIsRejectedAfterKnowledgeBaseIsDeleted() {
        completeCascadeDelete();

        assertThatThrownBy(() -> conversations
                .askAndStream(USER_ID, conversationId, new AskQuestionRequest("还能提问吗", "req-1"))
                .blockLast())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库已删除");
    }

    @Test
    void conversationsOfLivingKnowledgeBaseAreNotMarkedAsDeleted() {
        ConversationResponse conversation = conversations.get(USER_ID, conversationId);

        assertThat(conversation.knowledgeBaseDeleted()).isFalse();
        assertThat(conversation.knowledgeBaseName()).isEqualTo("产品手册");
    }
}
