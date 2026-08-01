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

public final class InMemoryRepositories {
    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeBases knowledgeBaseMapper;
    private final ConversationService conversations;
    private final List<String> conversationAuditTrail = new ArrayList<>();

    public InMemoryRepositories() {
        knowledgeBaseMapper = new KnowledgeBases();
        knowledgeBases = new KnowledgeBaseServiceImpl(knowledgeBaseMapper, (operation, id) -> { });
        RagAnswerPort emptyRag = (knowledgeBaseId, question, requestId, generationId) -> Flux.just(
                new AnswerEvent.Started(generationId),
                new AnswerEvent.Refused(generationId, "该知识库暂无可用文档"));
        QaRecordService records = new QaRecordServiceImpl(new QaRecords());
        DocumentService documents = Mockito.mock(DocumentService.class);
        Mockito.when(documents.hasAvailableDocuments(Mockito.anyLong())).thenReturn(true);
        ConversationAudit audit = (conversationId, removedRecordCount) ->
                conversationAuditTrail.add("deleted:" + conversationId + ":" + removedRecordCount);
        conversations = new ConversationServiceImpl(new Conversations(), knowledgeBases, documents, records, emptyRag,
                audit);
    }

    public KnowledgeBaseService knowledgeBases() { return knowledgeBases; }
    public void setKnowledgeBaseStatus(String name, KnowledgeBaseStatus status) {
        knowledgeBaseMapper.setStatus(name, status);
    }
    public ConversationService conversations() { return conversations; }
    public List<String> conversationAuditTrail() { return List.copyOf(conversationAuditTrail); }

    /** 用自定义 RAG 端口构造一套独立的会话服务，便于观察检索入参。 */
    public ConversationService conversationsWith(RagAnswerPort rag) {
        DocumentService documents = Mockito.mock(DocumentService.class);
        Mockito.when(documents.hasAvailableDocuments(Mockito.anyLong())).thenReturn(true);
        return new ConversationServiceImpl(new Conversations(), knowledgeBases, documents,
                new QaRecordServiceImpl(new QaRecords()), rag,
                (conversationId, removedRecordCount) -> { });
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
        public QaRecordEntity findByGenerationId(String generationId) { return values.get(generationId); }
        public List<QaRecordEntity> findByConversation(long conversationId) {
            return values.values().stream()
                    .filter(value -> value.getConversationId() == conversationId)
                    .sorted(Comparator.comparing(QaRecordEntity::getId))
                    .toList();
        }
        public int countByConversation(long conversationId) { return findByConversation(conversationId).size(); }
        public int deleteByConversation(long conversationId) {
            List<QaRecordEntity> removed = findByConversation(conversationId);
            removed.forEach(value -> values.remove(value.getGenerationId()));
            return removed.size();
        }
    }
}
