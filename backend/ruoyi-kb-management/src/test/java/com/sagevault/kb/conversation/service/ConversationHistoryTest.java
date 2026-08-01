package com.sagevault.kb.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.domain.RenameConversationRequest;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.support.InMemoryRepositories;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 会话历史与归属：列表隔离、首问默认标题、改名保留、删除级联与无正文审计。
 */
class ConversationHistoryTest {
    private static final long OWNER = 7L;
    private static final long OTHER = 8L;

    private InMemoryRepositories repositories;
    private ConversationService conversations;
    private long knowledgeBaseId;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        conversations = repositories.conversations();
        knowledgeBaseId = repositories.knowledgeBases()
                .create(new CreateKnowledgeBaseRequest("产品知识库", "描述")).id();
    }

    @Test
    void listReturnsOnlyOwnConversationsMostRecentFirst() {
        ConversationResponse first = create(OWNER);
        ConversationResponse second = create(OWNER);
        create(OTHER);

        List<ConversationResponse> owned = conversations.list(OWNER);

        assertThat(owned).extracting(ConversationResponse::userId).containsOnly(OWNER);
        assertThat(owned).extracting(ConversationResponse::id)
                .containsExactly(second.id(), first.id());
    }

    @Test
    void firstQuestionBecomesTheDefaultTitle() {
        ConversationResponse conversation = create(OWNER);

        ask(conversation.id(), "如何申请年假？", "request-1");

        assertThat(conversations.get(OWNER, conversation.id()).title()).isEqualTo("如何申请年假？");
    }

    @Test
    void laterQuestionsDoNotOverwriteAnExistingTitle() {
        ConversationResponse conversation = create(OWNER);
        ask(conversation.id(), "第一个问题", "request-1");

        ask(conversation.id(), "第二个问题", "request-2");

        assertThat(conversations.get(OWNER, conversation.id()).title()).isEqualTo("第一个问题");
    }

    @Test
    void renamedTitleSurvivesFollowUpQuestions() {
        ConversationResponse conversation = create(OWNER);
        ask(conversation.id(), "第一个问题", "request-1");
        conversations.rename(OWNER, conversation.id(), new RenameConversationRequest("年假政策"));

        ask(conversation.id(), "第二个问题", "request-2");

        assertThat(conversations.get(OWNER, conversation.id()).title()).isEqualTo("年假政策");
    }

    @Test
    void historyReturnsQuestionsOfTheConversationInAskedOrder() {
        ConversationResponse conversation = create(OWNER);
        ask(conversation.id(), "第一个问题", "request-1");
        ask(conversation.id(), "第二个问题", "request-2");

        assertThat(conversations.history(OWNER, conversation.id()))
                .extracting(record -> record.question())
                .containsExactly("第一个问题", "第二个问题");
    }

    @Test
    void anotherUserCannotReadRenameOrDeleteTheConversation() {
        ConversationResponse conversation = create(OWNER);

        assertThatThrownBy(() -> conversations.get(OTHER, conversation.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.CONVERSATION_FORBIDDEN);
        assertThatThrownBy(() -> conversations.history(OTHER, conversation.id()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> conversations.rename(OTHER, conversation.id(),
                new RenameConversationRequest("别人的标题")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> conversations.delete(OTHER, conversation.id()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteRemovesConversationAndItsQuestionBodiesAndAuditsWithoutBody() {
        ConversationResponse conversation = create(OWNER);
        ask(conversation.id(), "涉密的问题正文", "request-1");

        conversations.delete(OWNER, conversation.id());

        assertThat(conversations.list(OWNER)).isEmpty();
        assertThatThrownBy(() -> conversations.get(OWNER, conversation.id()))
                .isInstanceOf(BusinessException.class);
        assertThat(repositories.conversationAuditTrail())
                .containsExactly("deleted:" + conversation.id() + ":1");
        assertThat(repositories.conversationAuditTrail())
                .noneMatch(entry -> entry.contains("涉密的问题正文"));
    }

    @Test
    void everyQuestionRetrievesTheBoundKnowledgeBaseWithoutHistoryContext() {
        RecordingRag rag = new RecordingRag();
        ConversationService service = repositories.conversationsWith(rag);
        ConversationResponse conversation = service.create(OWNER, new CreateConversationRequest(knowledgeBaseId));
        service.ask(OWNER, conversation.id(), new AskQuestionRequest("第一个问题", "request-1")).collectList().block();
        service.ask(OWNER, conversation.id(), new AskQuestionRequest("第二个问题", "request-2")).collectList().block();

        assertThat(rag.knowledgeBaseIds).containsExactly(knowledgeBaseId, knowledgeBaseId);
        assertThat(rag.questions).containsExactly("第一个问题", "第二个问题");
    }

    @Test
    void renameRejectsBlankTitle() {
        ConversationResponse conversation = create(OWNER);

        assertThatThrownBy(() -> conversations.rename(OWNER, conversation.id(),
                new RenameConversationRequest("  ")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private ConversationResponse create(long userId) {
        return conversations.create(userId, new CreateConversationRequest(knowledgeBaseId));
    }

    private void ask(long conversationId, String question, String requestId) {
        conversations.ask(OWNER, conversationId, new AskQuestionRequest(question, requestId))
                .collectList().block();
    }

    /** 记录 RAG 端口收到的入参，用于证明历史消息没有被带入检索。 */
    private static final class RecordingRag implements RagAnswerPort {
        private final List<Long> knowledgeBaseIds = new ArrayList<>();
        private final List<String> questions = new ArrayList<>();

        @Override
        public Flux<AnswerEvent> answer(long knowledgeBaseId, String question, String requestId, String generationId) {
            knowledgeBaseIds.add(knowledgeBaseId);
            questions.add(question);
            return Flux.just(new AnswerEvent.Started(generationId), new AnswerEvent.Completed(generationId));
        }
    }
}
