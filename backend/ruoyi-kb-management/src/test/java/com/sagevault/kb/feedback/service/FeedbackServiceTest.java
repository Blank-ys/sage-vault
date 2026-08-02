package com.sagevault.kb.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.feedback.domain.FeedbackCategory;
import com.sagevault.kb.feedback.domain.FeedbackResponse;
import com.sagevault.kb.feedback.domain.SubmitFeedbackRequest;
import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import com.sagevault.kb.support.InMemoryRepositories;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedbackServiceTest {
    private static final long OWNER = 7L;
    private static final long OTHER_USER = 8L;

    private InMemoryRepositories repositories;
    private ConversationService conversations;
    private FeedbackService feedbacks;
    private long conversationId;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        conversations = repositories.conversations();
        feedbacks = repositories.feedbacks();
        long knowledgeBaseId = repositories.knowledgeBases()
                .create(new CreateKnowledgeBaseRequest("产品手册", "")).id();
        conversationId = conversations.create(OWNER, new CreateConversationRequest(knowledgeBaseId)).id();
    }

    @Test
    void acceptsFeedbackFromTheOwnerOnceConsentIsGiven() {
        long qaId = answeredRecordId();

        FeedbackResponse response = feedbacks.submit(OWNER, qaId,
                new SubmitFeedbackRequest("WRONG_ANSWER", "  答案与文档不一致  ", true));

        assertThat(response.qaId()).isEqualTo(qaId);
        assertThat(response.category()).isEqualTo(FeedbackCategory.WRONG_ANSWER);
        assertThat(response.comment()).isEqualTo("答案与文档不一致");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    void refusesFeedbackOnSomeoneElsesQaRecord() {
        long qaId = answeredRecordId();

        assertThatThrownBy(() -> feedbacks.submit(OTHER_USER, qaId,
                new SubmitFeedbackRequest("WRONG_ANSWER", "", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权对该问答提交反馈");
    }

    @Test
    void hidesWhetherAnUnknownQaRecordExists() {
        assertThatThrownBy(() -> feedbacks.submit(OWNER, 999999L,
                new SubmitFeedbackRequest("OTHER", "", true)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_FORBIDDEN);
    }

    @Test
    void refusesToStoreAnythingWithoutExplicitConsentToShare() {
        long qaId = answeredRecordId();

        assertThatThrownBy(() -> feedbacks.submit(OWNER, qaId,
                new SubmitFeedbackRequest("WRONG_ANSWER", "内容不该被保存", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("需要同意共享问答内容后才能提交反馈");

        // 未同意时不得留下任何反馈痕迹
        assertThat(feedbacks.findSubmitted(List.of(qaId))).isEmpty();
    }

    @Test
    void acceptsAtMostOneFeedbackPerQaRecord() {
        long qaId = answeredRecordId();
        feedbacks.submit(OWNER, qaId, new SubmitFeedbackRequest("OTHER", "第一次", true));

        assertThatThrownBy(() -> feedbacks.submit(OWNER, qaId, new SubmitFeedbackRequest("OTHER", "第二次", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("该问答已提交过反馈");
    }

    @Test
    void rejectsCategoriesOutsideTheClosedSet() {
        long qaId = answeredRecordId();

        assertThatThrownBy(() -> feedbacks.submit(OWNER, qaId,
                new SubmitFeedbackRequest("SOMETHING_ELSE", "", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("反馈类别不合法");
    }

    @Test
    void rejectsCommentsLongerThanTheStorageLimit() {
        long qaId = answeredRecordId();

        assertThatThrownBy(() -> feedbacks.submit(OWNER, qaId,
                new SubmitFeedbackRequest("OTHER", "长".repeat(1001), true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("反馈说明长度超过限制");
    }

    @Test
    void historyShowsWhichAnswersAlreadyHaveFeedback() {
        long qaId = answeredRecordId();
        feedbacks.submit(OWNER, qaId, new SubmitFeedbackRequest("OTHER", "", true));

        List<QaRecordResponse> history = conversations.history(OWNER, conversationId);

        assertThat(history).singleElement()
                .satisfies(record -> assertThat(record.feedbackSubmitted()).isTrue());
    }

    @Test
    void removesFeedbackContentWhenTheUserDeletesTheConversation() {
        long qaId = answeredRecordId();
        feedbacks.submit(OWNER, qaId, new SubmitFeedbackRequest("OTHER", "希望被彻底删除", true));

        conversations.delete(OWNER, conversationId);

        assertThat(feedbacks.findSubmitted(List.of(qaId))).isEmpty();
    }

    /** 走真实问答流程产生一条终态问答，反馈只针对这种已完成的问答。 */
    private long answeredRecordId() {
        conversations.askAndStream(OWNER, conversationId, new AskQuestionRequest("问题", "request-1"))
                .collectList().block();
        List<QaRecordResponse> history = conversations.history(OWNER, conversationId);
        return history.get(history.size() - 1).id();
    }
}
