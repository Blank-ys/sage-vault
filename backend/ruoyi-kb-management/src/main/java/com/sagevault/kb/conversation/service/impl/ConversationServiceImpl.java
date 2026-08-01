package com.sagevault.kb.conversation.service.impl;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AnswerStateSnapshot;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.domain.RenameConversationRequest;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.conversation.service.port.ConversationAudit;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.service.QaRecordService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Service
public class ConversationServiceImpl implements ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final ConversationMapper mapper;
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentService documents;
    private final QaRecordService records;
    private final RagAnswerPort rag;
    private final ConversationAudit audit;

    private static final String NO_AVAILABLE_DOCUMENTS_MESSAGE = "该知识库暂无可用文档";
    private static final int TITLE_MAX_LENGTH = 200;

    /** 本节点持有的在途流；停止命令只能中断本节点的订阅，跨节点由终态裁决兜底。 */
    private final Map<String, StopSignal> stopSignals = new ConcurrentHashMap<>();

    public ConversationServiceImpl(ConversationMapper mapper, KnowledgeBaseService knowledgeBases,
            DocumentService documents, QaRecordService records, RagAnswerPort rag, ConversationAudit audit) {
        this.mapper = mapper;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.records = records;
        this.rag = rag;
        this.audit = audit;
    }

    @Override
    public ConversationResponse create(long userId, CreateConversationRequest request) {
        knowledgeBases.requireAvailable(request.knowledgeBaseId());
        ConversationEntity entity = new ConversationEntity();
        entity.setUserId(userId);
        entity.setKnowledgeBaseId(request.knowledgeBaseId());
        entity.setTitle("");
        mapper.insert(entity);
        return response(mapper.findById(entity.getId()));
    }

    @Override
    public List<ConversationResponse> list(long userId) {
        return mapper.findByUser(userId).stream().map(ConversationServiceImpl::response).toList();
    }

    @Override
    public ConversationResponse get(long userId, long conversationId) {
        return response(requireOwned(userId, conversationId));
    }

    @Override
    public List<QaRecordResponse> history(long userId, long conversationId) {
        requireOwned(userId, conversationId);
        return records.listByConversation(conversationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationResponse rename(long userId, long conversationId, RenameConversationRequest request) {
        requireOwned(userId, conversationId);
        String title = normalizeTitle(request == null ? null : request.title());
        mapper.updateTitle(conversationId, userId, title);
        return response(mapper.findById(conversationId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(long userId, long conversationId) {
        requireOwned(userId, conversationId);
        int removed = records.deleteByConversation(conversationId);
        if (mapper.deleteOwned(conversationId, userId) == 0) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        audit.recordDeleted(conversationId, removed);
    }

    @Override
    public AnswerStateSnapshot getAnswerState(long userId, long conversationId, String generationId) {
        if (generationId == null || generationId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "生成标识不能为空");
        }
        requireOwned(userId, conversationId);
        QaRecordEntity record = records.findByGenerationId(generationId);
        if (record == null || record.getConversationId() != conversationId) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_READY, "未找到该次回答，或回答尚未完成");
        }
        QaRecordStatus status = record.getStatus();
        if (status == null) {
            throw new BusinessException(ErrorCode.ANSWER_UNKNOWN_STATUS, "问答记录状态未知");
        }
        boolean terminal = status != QaRecordStatus.STARTED;
        String answer = terminal ? record.getAnswer() : null;
        return new AnswerStateSnapshot(generationId, terminal, status, answer);
    }

    @Override
    public AnswerStateSnapshot stopAnswer(long userId, long conversationId, String generationId) {
        if (generationId == null || generationId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "生成标识不能为空");
        }
        requireOwned(userId, conversationId);
        QaRecordEntity record = records.findByGenerationId(generationId);
        if (record == null || record.getConversationId() != conversationId) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_READY, "未找到该次回答，或回答尚未完成");
        }
        // 先裁决终态：赢得迁移的调用才是真正的停止者，重复停止或已终态一律拒绝。
        if (!records.markStopped(generationId)) {
            throw new BusinessException(ErrorCode.ANSWER_NOT_STOPPABLE, "该回答已结束，无法停止");
        }
        // 终态已定，随后的通知都是尽力而为：失败只记录，不回滚业务结论。
        StopSignal signal = stopSignals.get(generationId);
        if (signal != null) {
            signal.trigger();
        }
        rag.cancel(generationId, UUID.randomUUID().toString())
                .onErrorResume(error -> {
                    log.warn("Best-effort cancel failed after the answer was already stopped: generationId={}",
                            generationId, error);
                    return Mono.just(false);
                })
                .subscribe(cancelled -> {
                    if (!cancelled) {
                        log.info("RAG did not confirm cancellation: generationId={}", generationId);
                    }
                });
        return getAnswerState(userId, conversationId, generationId);
    }

    @Override
    public Flux<AnswerEvent> askAndStream(long userId, long conversationId, AskQuestionRequest request) {
        if (request.question() == null || request.question().isBlank()
                || request.requestId() == null || request.requestId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "问题或请求标识不能为空");
        }
        // 短事务：悲观锁 + 并发串行化 + 落库 STARTED 记录。不在此处调用外部服务。
        BeginAnswer begin = beginAnswer(userId, conversationId, request);

        // 知识库无可用文档时直接拒答，不进入流式检索。
        if (!documents.hasAvailableDocuments(begin.knowledgeBaseId())) {
            records.markRefused(begin.generationId(), NO_AVAILABLE_DOCUMENTS_MESSAGE);
            return Flux.just(
                new AnswerEvent.Started(begin.generationId()),
                new AnswerEvent.Refused(begin.generationId(), NO_AVAILABLE_DOCUMENTS_MESSAGE)
            );
        }

        long knowledgeBaseId = begin.knowledgeBaseId();
        String generationId = begin.generationId();
        AtomicBoolean terminal = new AtomicBoolean();
        StringBuilder answer = new StringBuilder();
        // 停止信号由 stopAnswer 触发；订阅结束时必须清理，避免 generationId 累积。
        StopSignal stopSignal = new StopSignal(generationId);
        stopSignals.put(generationId, stopSignal);
        return Flux.defer(() -> rag.answer(knowledgeBaseId, request.question(), request.requestId(), generationId))
                .doOnNext(event -> {
                    if (event instanceof AnswerEvent.Delta delta) {
                        records.appendAnswer(generationId, delta.delta());
                        answer.append(delta.delta());
                    } else if (event instanceof AnswerEvent.Completed) {
                        records.markCompleted(generationId, answer.toString());
                        terminal.set(true);
                    } else if (event instanceof AnswerEvent.Refused refused) {
                        records.markRefused(generationId, refused.message());
                        terminal.set(true);
                    } else if (event instanceof AnswerEvent.Stopped) {
                        // Python 已确认停止；终态已由 stopAnswer 裁决，此处仅停止后续处理。
                        terminal.set(true);
                    }
                })
                // 停止命令一旦裁决成功就立即结束上游并补发 stopped，不依赖 Python 的响应速度。
                .takeUntilOther(stopSignal.triggered())
                .concatWith(stopSignal.trailingEvent())
                .doOnComplete(() -> markUnfinishedIfNeeded(generationId, terminal, stopSignal))
                .doOnError(error -> markUnfinishedIfNeeded(generationId, terminal, stopSignal))
                // 连接断开不是业务取消：只裁决未完成，不向 Python 发送停止命令。
                .doOnCancel(() -> markUnfinishedIfNeeded(generationId, terminal, stopSignal))
                .doFinally(signal -> stopSignals.remove(generationId, stopSignal));
    }

    /**
     * 在悲观锁（FOR UPDATE）保护下完成：会话归属校验、知识库可用性校验、并发串行化检查，
     * 以及落库 STARTED 问答记录。事务提交后即释放锁；后续的流式检索在事务之外执行，
     * 以避免在事务内调用外部服务。返回的 generationId 已持久化，后续并发请求可通过
     * 进行中记录计数感知到本次回答。
     */
    @Transactional(rollbackFor = Exception.class)
    BeginAnswer beginAnswer(long userId, long conversationId, AskQuestionRequest request) {
        requireOwned(userId, conversationId);
        ConversationEntity conversation = mapper.selectForStreaming(conversationId, userId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        knowledgeBases.requireAvailable(conversation.getKnowledgeBaseId());
        if (records.hasPending(conversationId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_CONCURRENCY_CONFLICT,
                    "同一会话已有进行中的回答，请等待其完成后再发起");
        }
        boolean firstQuestion = !records.hasRecords(conversationId);
        String generationId = UUID.randomUUID().toString();
        try {
            records.create(conversationId, userId, request.requestId(), generationId, request.question());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "请求已处理", exception);
        }
        applyDefaultTitle(conversation, firstQuestion, request.question());
        return new BeginAnswer(conversationId, conversation.getKnowledgeBaseId(), generationId);
    }

    private record BeginAnswer(long conversationId, long knowledgeBaseId, String generationId) { }

    /**
     * 单次生成的停止开关。触发前 {@link #triggered()} 保持静默、{@link #trailingEvent()} 为空，
     * 因此正常结束的流不会被它挂起；触发后前者结束上游、后者补发一条 stopped 事件。
     */
    private static final class StopSignal {
        private final String generationId;
        private final Sinks.One<AnswerEvent> sink = Sinks.one();
        private final AtomicBoolean fired = new AtomicBoolean();

        private StopSignal(String generationId) {
            this.generationId = generationId;
        }

        private void trigger() {
            if (fired.compareAndSet(false, true)) {
                sink.tryEmitValue(new AnswerEvent.Stopped(generationId));
            }
        }

        private boolean isFired() {
            return fired.get();
        }

        private Mono<AnswerEvent> triggered() {
            return sink.asMono();
        }

        private Flux<AnswerEvent> trailingEvent() {
            return Flux.defer(() -> fired.get()
                    ? Flux.just(new AnswerEvent.Stopped(generationId))
                    : Flux.empty());
        }
    }

    /**
     * 首个提问生成默认标题；已有标题（含用户改名）不被覆盖。后续提问只推进最近活跃时间。
     */
    private void applyDefaultTitle(ConversationEntity conversation, boolean firstQuestion, String question) {
        if (firstQuestion && isBlank(conversation.getTitle())) {
            mapper.updateTitle(conversation.getId(), conversation.getUserId(), truncate(question.strip()));
            return;
        }
        mapper.touch(conversation.getId());
    }

    private static String normalizeTitle(String title) {
        if (isBlank(title)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "会话标题不能为空");
        }
        return truncate(title.strip());
    }

    private static String truncate(String value) {
        return value.length() > TITLE_MAX_LENGTH ? value.substring(0, TITLE_MAX_LENGTH) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ConversationEntity requireOwned(long userId, long conversationId) {
        ConversationEntity conversation = mapper.findById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        if (conversation.getUserId() != userId) {
            throw new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "无权访问该会话");
        }
        return conversation;
    }

    private static ConversationResponse response(ConversationEntity entity) {
        return new ConversationResponse(entity.getId(), entity.getUserId(), entity.getKnowledgeBaseId(),
                entity.getTitle() == null ? "" : entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * 流结束时的兜底裁决。已停止的回答终态由 {@code stopAnswer} 决定，这里不得再改写；
     * 其余未产出终态事件的结束（含连接断开、上游异常）一律裁决为未完成。
     */
    private void markUnfinishedIfNeeded(String generationId, AtomicBoolean terminal, StopSignal stopSignal) {
        if (stopSignal.isFired()) {
            return;
        }
        if (terminal.compareAndSet(false, true)) {
            records.markUnfinished(generationId);
        }
    }
}
