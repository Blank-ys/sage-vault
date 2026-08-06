package com.sagevault.kb.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.service.AnswerSessionService;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.feedback.domain.AdminFeedbackDetail;
import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import com.sagevault.kb.feedback.domain.ResolveFeedbackRequest;
import com.sagevault.kb.feedback.domain.SubmitFeedbackRequest;
import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import com.sagevault.kb.support.InMemoryRepositories;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 管理端反馈处理的服务层行为，重点是隐私边界与处理状态流转。 */
class AdminFeedbackServiceTest {
    private static final long OWNER = 7L;
    private static final long ADMIN = 900L;

    private InMemoryRepositories repositories;
    private ConversationService conversations;
    private AnswerSessionService answerSessions;
    private FeedbackService feedbacks;
    private long conversationId;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        conversations = repositories.conversations();
        answerSessions = repositories.answerSessions();
        feedbacks = repositories.feedbacks();
        long knowledgeBaseId = repositories.knowledgeBases()
                .create(new CreateKnowledgeBaseRequest("产品手册", "")).id();
        conversationId = conversations.create(OWNER, new CreateConversationRequest(knowledgeBaseId)).id();
    }

    @Test
    void newFeedbackWaitsInThePendingQueue() {
        feedbacks.submit(OWNER, answeredRecordId(), submit("金额不对"));

        var pending = feedbacks.listForAdmin(AdminFeedbackQuery.of(FeedbackStatus.PENDING, 1, 20));

        assertThat(pending.total()).isEqualTo(1);
        assertThat(pending.items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(FeedbackStatus.PENDING));
    }

    @Test
    void theQueueListingDoesNotCarryQuestionOrAnswerText() {
        feedbacks.submit(OWNER, answeredRecordId(), submit("说明"));

        var page = feedbacks.listForAdmin(AdminFeedbackQuery.of(null, 1, 20));

        // 列表只用于排队，正文仅在管理员打开详情时才返回，压缩正文暴露面
        assertThat(page.items()).singleElement()
                .satisfies(item -> assertThat(item.toString()).doesNotContain("答案"));
    }

    @Test
    void theQueueIsPaged() {
        feedbacks.submit(OWNER, answeredRecordId(), submit("第一条"));
        feedbacks.submit(OWNER, answeredRecordId(), submit("第二条"));

        var firstPage = feedbacks.listForAdmin(AdminFeedbackQuery.of(null, 1, 1));

        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(feedbacks.listForAdmin(AdminFeedbackQuery.of(null, 2, 1)).items()).hasSize(1);
    }

    @Test
    void anAdminReadsTheSharedQuestionAndAnswerThroughTheFeedback() {
        long qaId = answeredRecordId();
        var submitted = feedbacks.submit(OWNER, qaId, submit("答案不对"));

        AdminFeedbackDetail detail = feedbacks.findDetailForAdmin(ADMIN, submitted.id());

        assertThat(detail.qaId()).isEqualTo(qaId);
        assertThat(detail.question()).isEqualTo("问题");
        assertThat(detail.answer()).isNotBlank();
        assertThat(detail.requestId()).isEqualTo("request-1");
    }

    @Test
    void aQaRecordWithoutFeedbackHasNoAdminEntryPointAtAll() {
        long unreported = answeredRecordId();

        // 详情的键是反馈 ID；未提交反馈的问答不存在这样的键，正文无法被管理端检索到
        assertThatThrownBy(() -> feedbacks.findDetailForAdmin(ADMIN, unreported))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_NOT_FOUND);
        assertThat(feedbacks.listForAdmin(AdminFeedbackQuery.of(null, 1, 20)).total()).isZero();
    }

    @Test
    void resolvingMovesTheFeedbackOutOfPendingAndKeepsTheInternalNote() {
        var submitted = feedbacks.submit(OWNER, answeredRecordId(), submit("说明"));

        AdminFeedbackDetail resolved = feedbacks.resolve(ADMIN, submitted.id(),
                new ResolveFeedbackRequest(FeedbackStatus.RESOLVED, "已核实并修正文档"));

        assertThat(resolved.status()).isEqualTo(FeedbackStatus.RESOLVED);
        assertThat(resolved.adminNote()).isEqualTo("已核实并修正文档");
        assertThat(feedbacks.listForAdmin(AdminFeedbackQuery.of(FeedbackStatus.PENDING, 1, 20)).total())
                .isZero();
        assertThat(feedbacks.listForAdmin(AdminFeedbackQuery.of(FeedbackStatus.RESOLVED, 1, 20)).total())
                .isEqualTo(1);
    }

    @Test
    void aResolvedFeedbackCanBeReopened() {
        var submitted = feedbacks.submit(OWNER, answeredRecordId(), submit("说明"));
        feedbacks.resolve(ADMIN, submitted.id(), new ResolveFeedbackRequest(FeedbackStatus.RESOLVED, "已处理"));

        AdminFeedbackDetail reopened = feedbacks.resolve(ADMIN, submitted.id(),
                new ResolveFeedbackRequest(FeedbackStatus.PENDING, "复核后重开"));

        assertThat(reopened.status()).isEqualTo(FeedbackStatus.PENDING);
        assertThat(reopened.adminNote()).isEqualTo("复核后重开");
    }

    @Test
    void resolvingAFeedbackThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> feedbacks.resolve(ADMIN, 4040L,
                        new ResolveFeedbackRequest(FeedbackStatus.RESOLVED, "")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_NOT_FOUND);
    }

    @Test
    void anInternalNoteLongerThanTheStorageLimitIsRefused() {
        var submitted = feedbacks.submit(OWNER, answeredRecordId(), submit("说明"));

        assertThatThrownBy(() -> feedbacks.resolve(ADMIN, submitted.id(),
                        new ResolveFeedbackRequest(FeedbackStatus.RESOLVED, "长".repeat(1001))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内部备注长度超过限制");
    }

    @Test
    void adminActionsAreAuditedWithIdentifiersOnly() {
        long qaId = answeredRecordId();
        var submitted = feedbacks.submit(OWNER, qaId, submit("机密说明"));

        feedbacks.findDetailForAdmin(ADMIN, submitted.id());
        feedbacks.resolve(ADMIN, submitted.id(), new ResolveFeedbackRequest(FeedbackStatus.RESOLVED, "机密备注"));

        assertThat(repositories.feedbackAuditTrail()).containsExactly(
                "viewed:" + submitted.id() + ":" + qaId,
                "resolved:" + submitted.id() + ":RESOLVED");
        // 审计只记录标识，问答与反馈正文不得随操作日志扩散
        assertThat(String.join("|", repositories.feedbackAuditTrail()))
                .doesNotContain("机密说明")
                .doesNotContain("机密备注")
                .doesNotContain("问题");
    }

    @Test
    void deletingTheConversationRemovesTheSharedContentFromTheAdminQueue() {
        var submitted = feedbacks.submit(OWNER, answeredRecordId(), submit("希望被彻底删除"));
        assertThat(feedbacks.listForAdmin(AdminFeedbackQuery.of(null, 1, 20)).total()).isEqualTo(1);

        conversations.delete(OWNER, conversationId);

        // 用户撤回会话后，管理端不再保留其问答正文
        assertThat(feedbacks.listForAdmin(AdminFeedbackQuery.of(null, 1, 20)).total()).isZero();
        assertThatThrownBy(() -> feedbacks.findDetailForAdmin(ADMIN, submitted.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.FEEDBACK_NOT_FOUND);
    }

    @Test
    void diagnosticsAreEmptyUntilTheCrossLanguageCollectionLandsIn11c() {
        var submitted = feedbacks.submit(OWNER, answeredRecordId(), submit("说明"));

        AdminFeedbackDetail detail = feedbacks.findDetailForAdmin(ADMIN, submitted.id());

        // 请求 ID 已可用；片段标识/分数与阶段耗时的采集链路由 11c 建立
        assertThat(detail.requestId()).isNotBlank();
        assertThat(detail.retrievalDiagnostics()).isEmpty();
        assertThat(detail.stageDurations()).isEmpty();
    }

    private static SubmitFeedbackRequest submit(String comment) {
        return new SubmitFeedbackRequest("WRONG_ANSWER", comment, true);
    }

    /** 走真实问答流程产生一条终态问答，反馈只针对这种已完成的问答。 */
    private long answeredRecordId() {
        answerSessions.askAndStream(OWNER, conversationId, new AskQuestionRequest("问题", "request-1"))
                .collectList().block();
        List<QaRecordResponse> history = conversations.history(OWNER, conversationId);
        return history.get(history.size() - 1).id();
    }
}
