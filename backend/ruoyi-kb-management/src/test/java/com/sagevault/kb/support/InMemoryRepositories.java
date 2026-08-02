package com.sagevault.kb.support;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.conversation.service.impl.ConversationServiceImpl;
import com.sagevault.kb.conversation.service.port.ConversationAudit;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.mapper.KnowledgeBaseMapper;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.knowledgebase.service.impl.KnowledgeBaseServiceImpl;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.feedback.domain.FeedbackEntity;
import com.sagevault.kb.feedback.mapper.FeedbackMapper;
import com.sagevault.kb.feedback.service.FeedbackService;
import com.sagevault.kb.feedback.service.impl.FeedbackServiceImpl;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.service.QaRecordService;
import com.sagevault.kb.qarecord.service.impl.QaRecordServiceImpl;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class InMemoryRepositories {
    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeBases knowledgeBaseMapper;
    private final ConversationService conversations;
    private final FeedbackService feedbacks;
    private final List<String> conversationAuditTrail = new ArrayList<>();

    public InMemoryRepositories() {
        knowledgeBaseMapper = new KnowledgeBases();
        knowledgeBases = new KnowledgeBaseServiceImpl(knowledgeBaseMapper, (operation, id) -> { });
        RagAnswerPort emptyRag = new RagAnswerPort() {
            @Override
            public Flux<AnswerEvent> answer(long knowledgeBaseId, String question, String requestId,
                    String generationId) {
                return Flux.just(new AnswerEvent.Started(generationId),
                        new AnswerEvent.Refused(generationId, "该知识库暂无可用文档"));
            }

            @Override
            public Mono<Boolean> cancel(String generationId, String requestId) {
                return Mono.just(false);
            }
        };
        Feedbacks feedbackMapper = new Feedbacks();
        QaRecords qaRecordMapper = new QaRecords(feedbackMapper);
        QaRecordService records = new QaRecordServiceImpl(qaRecordMapper);
        feedbacks = new FeedbackServiceImpl(feedbackMapper, qaRecordMapper);
        DocumentService documents = Mockito.mock(DocumentService.class);
        Mockito.when(documents.hasAvailableDocuments(Mockito.anyLong())).thenReturn(true);
        ConversationAudit audit = (conversationId, removedRecordCount) ->
                conversationAuditTrail.add("deleted:" + conversationId + ":" + removedRecordCount);
        conversations = new ConversationServiceImpl(new Conversations(), knowledgeBases, documents, records, emptyRag,
                audit, feedbacks);
    }

    public KnowledgeBaseService knowledgeBases() { return knowledgeBases; }
    public void setKnowledgeBaseStatus(String name, KnowledgeBaseStatus status) {
        knowledgeBaseMapper.setStatus(name, status);
    }
    public ConversationService conversations() { return conversations; }
    public FeedbackService feedbacks() { return feedbacks; }
    public List<String> conversationAuditTrail() { return List.copyOf(conversationAuditTrail); }

    /** 用自定义 RAG 端口构造一套独立的会话服务，便于观察检索入参。 */
    public ConversationService conversationsWith(RagAnswerPort rag) {
        DocumentService documents = Mockito.mock(DocumentService.class);
        Mockito.when(documents.hasAvailableDocuments(Mockito.anyLong())).thenReturn(true);
        Feedbacks feedbackMapper = new Feedbacks();
        QaRecords qaRecordMapper = new QaRecords(feedbackMapper);
        return new ConversationServiceImpl(new Conversations(), knowledgeBases, documents,
                new QaRecordServiceImpl(qaRecordMapper), rag,
                (conversationId, removedRecordCount) -> { },
                new FeedbackServiceImpl(feedbackMapper, qaRecordMapper));
    }

    private static final class KnowledgeBases implements KnowledgeBaseMapper {
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, KnowledgeBaseEntity> values = new LinkedHashMap<>();
        public int insert(KnowledgeBaseEntity value) {
            value.setId(ids.incrementAndGet());
            values.put(value.getId(), value);
            return 1;
        }
        public int update(KnowledgeBaseEntity value) { values.put(value.getId(), value); return 1; }
        public KnowledgeBaseEntity findById(long id) { return values.get(id); }
        public KnowledgeBaseEntity findByNormalizedName(String name) { return values.values().stream().filter(value -> value.getNormalizedName().equals(name)).findFirst().orElse(null); }
        public List<KnowledgeBaseEntity> findAll() { return new ArrayList<>(values.values()); }
        public List<KnowledgeBaseEntity> findByStatus(String status) { return values.values().stream().filter(value -> value.getStatus().name().equals(status)).toList(); }
        void setStatus(String name, KnowledgeBaseStatus status) {
            values.values().stream()
                    .filter(value -> value.getNormalizedName().equals(name.trim().toLowerCase(java.util.Locale.ROOT)))
                    .findFirst()
                    .ifPresent(value -> value.setStatus(status));
        }
    }

    private static final class Conversations implements ConversationMapper {
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, ConversationEntity> values = new LinkedHashMap<>();
        public int insert(ConversationEntity value) {
            value.setId(ids.incrementAndGet());
            value.setCreatedAt(LocalDateTime.now());
            value.setUpdatedAt(LocalDateTime.now());
            values.put(value.getId(), value);
            return 1;
        }
        public ConversationEntity findById(long id) { return values.get(id); }
        public ConversationEntity selectForStreaming(long id, long userId) {
            ConversationEntity value = values.get(id);
            return (value != null && value.getUserId() == userId) ? value : null;
        }
        public List<ConversationEntity> findByUser(long userId) {
            return values.values().stream()
                    .filter(value -> value.getUserId() == userId)
                    .sorted(Comparator.comparing(ConversationEntity::getUpdatedAt).reversed()
                            .thenComparing(Comparator.comparing(ConversationEntity::getId).reversed()))
                    .toList();
        }
        public int updateTitle(long id, long userId, String title) {
            ConversationEntity value = values.get(id);
            if (value == null || value.getUserId() != userId) { return 0; }
            value.setTitle(title);
            value.setUpdatedAt(LocalDateTime.now());
            return 1;
        }
        public int touch(long id) {
            ConversationEntity value = values.get(id);
            if (value == null) { return 0; }
            value.setUpdatedAt(LocalDateTime.now());
            return 1;
        }
        public int deleteOwned(long id, long userId) {
            ConversationEntity value = values.get(id);
            if (value == null || value.getUserId() != userId) { return 0; }
            values.remove(id);
            return 1;
        }
    }

    private static final class QaRecords implements QaRecordMapper {
        private final AtomicLong ids = new AtomicLong();
        private final Map<String, QaRecordEntity> values = new LinkedHashMap<>();
        private final Feedbacks feedbacks;
        QaRecords(Feedbacks feedbacks) { this.feedbacks = feedbacks; }
        public int insert(QaRecordEntity value) {
            value.setId(ids.incrementAndGet());
            value.setCreatedAt(LocalDateTime.now());
            values.put(value.getGenerationId(), value);
            return 1;
        }
        public int appendAnswer(String generationId, String delta) {
            QaRecordEntity value = values.get(generationId);
            if (value == null || value.getStatus() != QaRecordStatus.STARTED) { return 0; }
            value.setAnswer(value.getAnswer() + delta);
            return 1;
        }
        public int updateTerminalState(String generationId, QaRecordStatus status, String answer) {
            QaRecordEntity value = values.get(generationId);
            if (value == null || value.getStatus() != QaRecordStatus.STARTED) { return 0; }
            value.setStatus(status);
            value.setAnswer(answer);
            return 1;
        }
        public int updateTerminalStatusKeepingAnswer(String generationId, QaRecordStatus status) {
            QaRecordEntity value = values.get(generationId);
            if (value == null || value.getStatus() != QaRecordStatus.STARTED) { return 0; }
            value.setStatus(status);
            return 1;
        }
        public QaRecordEntity findByGenerationId(String generationId) { return values.get(generationId); }
        public QaRecordEntity findById(long id) {
            return values.values().stream().filter(value -> value.getId() == id).findFirst().orElse(null);
        }
        public List<QaRecordEntity> findByConversation(long conversationId) {
            return values.values().stream()
                    .filter(value -> value.getConversationId() == conversationId)
                    .sorted(Comparator.comparing(QaRecordEntity::getId))
                    .toList();
        }
        public int countByConversation(long conversationId) { return findByConversation(conversationId).size(); }
        public int countPendingByConversation(long conversationId) {
            return (int) findByConversation(conversationId).stream()
                    .filter(value -> value.getStatus() == QaRecordStatus.STARTED)
                    .count();
        }
        public int deleteByConversation(long conversationId) {
            List<QaRecordEntity> removed = findByConversation(conversationId);
            removed.forEach(value -> {
                values.remove(value.getGenerationId());
                // 镜像库层外键 ON DELETE CASCADE：问答删除后反馈正文不得残留。
                feedbacks.deleteByQaId(value.getId());
            });
            return removed.size();
        }
    }

    private static final class Feedbacks implements FeedbackMapper {
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, FeedbackEntity> byQaId = new LinkedHashMap<>();
        public int insert(FeedbackEntity value) {
            // 镜像 uk_sv_qa_feedback_qa 唯一键。
            if (byQaId.containsKey(value.getQaId())) {
                throw new org.springframework.dao.DuplicateKeyException("duplicate qa_id");
            }
            value.setId(ids.incrementAndGet());
            value.setCreatedAt(LocalDateTime.now());
            value.setUpdatedAt(LocalDateTime.now());
            byQaId.put(value.getQaId(), value);
            return 1;
        }
        public FeedbackEntity findByQaId(long qaId) { return byQaId.get(qaId); }
        public List<FeedbackEntity> findByQaIds(List<Long> qaIds) {
            return qaIds.stream().map(byQaId::get).filter(java.util.Objects::nonNull).toList();
        }
        void deleteByQaId(long qaId) { byQaId.remove(qaId); }
    }
}
