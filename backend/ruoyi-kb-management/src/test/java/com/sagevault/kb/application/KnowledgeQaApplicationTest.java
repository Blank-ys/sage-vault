package com.sagevault.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.domain.UpdateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.support.InMemoryRepositories;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeQaApplicationTest {
    private InMemoryRepositories repositories;
    private KnowledgeBaseService knowledgeBases;
    private ConversationService conversations;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        knowledgeBases = repositories.knowledgeBases();
        conversations = repositories.conversations();
    }

    @Test
    void knowledgeBaseNamesAreGloballyUniqueIgnoringCase() {
        knowledgeBases.create(new CreateKnowledgeBaseRequest("Research", "内部资料"));

        assertThatThrownBy(() -> knowledgeBases.create(
                new CreateKnowledgeBaseRequest("research", "重复名称")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称已存在");
    }

    @Test
    void knowledgeBaseNamesIgnoreLeadingAndTrailingWhitespaceForUniqueness() {
        knowledgeBases.create(new CreateKnowledgeBaseRequest("  Research  ", "内部资料"));

        assertThatThrownBy(() -> knowledgeBases.create(
                new CreateKnowledgeBaseRequest("RESEARCH", "重复名称")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称已存在");
    }

    @Test
    void generalUsersOnlySelectAvailableKnowledgeBases() {
        var available = knowledgeBases.create(new CreateKnowledgeBaseRequest("可用知识", ""));
        knowledgeBases.create(new CreateKnowledgeBaseRequest("删除中知识", ""));
        repositories.setKnowledgeBaseStatus("删除中知识", KnowledgeBaseStatus.DELETING);

        assertThat(knowledgeBases.listAvailable()).extracting("id").containsExactly(available.id());
    }

    @Test
    void administratorCanViewAndUpdateKnowledgeBaseWithoutChangingItsIdentity() {
        var created = knowledgeBases.create(new CreateKnowledgeBaseRequest("旧名称", "旧描述"));

        var updated = knowledgeBases.update(created.id(), new UpdateKnowledgeBaseRequest("新名称", "新描述"));

        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(knowledgeBases.get(created.id()).name()).isEqualTo("新名称");
    }

    @Test
    void conversationIsBoundToItsOwnerAndAvailableKnowledgeBase() {
        var knowledgeBase = knowledgeBases.create(new CreateKnowledgeBaseRequest("产品知识", ""));

        var conversation = conversations.create(7L,
                new CreateConversationRequest(knowledgeBase.id()));

        assertThat(conversation.userId()).isEqualTo(7L);
        assertThat(conversation.knowledgeBaseId()).isEqualTo(knowledgeBase.id());
    }

    @Test
    void emptyKnowledgeBaseReturnsStartedThenExplicitRefusal() {
        var knowledgeBase = knowledgeBases.create(new CreateKnowledgeBaseRequest("空知识", ""));
        var conversation = conversations.create(7L,
                new CreateConversationRequest(knowledgeBase.id()));

        List<AnswerEvent> events = conversations.askAndStream(7L, conversation.id(),
                new AskQuestionRequest("这里有什么内容？", "req-1")).collectList().block();

        assertThat(events).containsExactly(
                new AnswerEvent.Started(events.get(0).generationId()),
                new AnswerEvent.Refused(events.get(0).generationId(), "该知识库暂无可用文档"));
    }

    @Test
    void userCannotAskThroughAnotherUsersConversation() {
        var knowledgeBase = knowledgeBases.create(new CreateKnowledgeBaseRequest("隔离知识", ""));
        var conversation = conversations.create(7L, new CreateConversationRequest(knowledgeBase.id()));

        assertThatThrownBy(() -> conversations.askAndStream(8L, conversation.id(),
                new AskQuestionRequest("问题", "req-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void userCannotAskAfterConversationKnowledgeBaseBecomesUnavailable() {
        var knowledgeBase = knowledgeBases.create(new CreateKnowledgeBaseRequest("待删除知识", ""));
        var conversation = conversations.create(7L, new CreateConversationRequest(knowledgeBase.id()));
        repositories.setKnowledgeBaseStatus("待删除知识", KnowledgeBaseStatus.DELETING);

        assertThatThrownBy(() -> conversations.askAndStream(7L, conversation.id(),
                new AskQuestionRequest("问题", "req-3")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库当前不可用");
    }
}
