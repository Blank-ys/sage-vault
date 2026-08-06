package com.sagevault.kb.support;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.AnswerSessionService;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.conversation.service.impl.AnswerSessionServiceImpl;
import com.sagevault.kb.conversation.service.impl.ConversationServiceImpl;
import com.sagevault.kb.conversation.service.port.ConversationAudit;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.mapper.KnowledgeBaseMapper;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseCascadeDeleteTask;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.knowledgebase.service.impl.KnowledgeBaseServiceImpl;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.feedback.domain.AdminFeedbackDetailRow;
import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.FeedbackEntity;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import com.sagevault.kb.feedback.mapper.FeedbackMapper;
import com.sagevault.kb.feedback.service.FeedbackService;
import com.sagevault.kb.feedback.service.impl.FeedbackServiceImpl;
import com.sagevault.kb.feedback.service.port.FeedbackAudit;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.mapper.RetrievalDiagnosticMapper;
import com.sagevault.kb.qarecord.domain.RetrievalDiagnosticEntity;
import com.sagevault.kb.qarecord.service.QaRecordEvidenceService;
import com.sagevault.kb.qarecord.service.QaRecordService;
import com.sagevault.kb.qarecord.service.impl.QaRecordEvidenceServiceImpl;
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
    private final AnswerSessionService answerSessions;
    private final FeedbackService feedbacks;
    private final List<String> conversationAuditTrail = new ArrayList<>();
    private final List<String> feedbackAuditTrail = new ArrayList<>();
    private final List<Long> retriedContentCleanups = new ArrayList<>();
    private final KnowledgeBaseContentCleaner retryTrackingCleaner = new KnowledgeBaseContentCleaner() {
        @Override
        public CleanupProgress cleanupContent(long knowledgeBaseId) {
            // 服务层不负责推进清理轮次，这里只需满足接口
            return CleanupProgress.inProgress(0);
        }

        @Override
        public int retryFailedContent(long knowledgeBaseId) {
            retriedContentCleanups.add(knowledgeBaseId);
            return 0;
        }
    };

    public InMemoryRepositories() {
        knowledgeBaseMapper = new KnowledgeBases();
        knowledgeBases = new KnowledgeBaseServiceImpl(knowledgeBaseMapper,
                Mockito.mock(com.sagevault.kb.knowledgebase.service.port.ManagementAudit.class),
                retryTrackingCleaner);
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
        Diagnostics diagnostics = new Diagnostics();
        QaRecordService records = new QaRecordServiceImpl(qaRecordMapper, diagnostics);
        feedbacks = new FeedbackServiceImpl(feedbackMapper,
                new QaRecordEvidenceServiceImpl(qaRecordMapper, diagnostics), recordingFeedbackAudit());
        DocumentService documents = Mockito.mock(DocumentService.class);
        Mockito.when(documents.hasAvailableDocuments(Mockito.anyLong())).thenReturn(true);
        ConversationAudit audit = (conversationId, removedRecordCount) ->
                conversationAuditTrail.add("deleted:" + conversationId + ":" + removedRecordCount);
        Conversations conversationsMapper = new Conversations();
        answerSessions = new AnswerSessionServiceImpl(conversationsMapper, knowledgeBases, documents, records, emptyRag);
        conversations = new ConversationServiceImpl(conversationsMapper, knowledgeBases, records, feedbacks, audit);
    }

    public KnowledgeBaseService knowledgeBases() { return knowledgeBases; }
    /** 暴露底层 mapper，供断言 cleanup_attempt 等清理预算状态。 */
    public KnowledgeBaseMapper knowledgeBaseMapper() { return knowledgeBaseMapper; }
    public void setKnowledgeBaseStatus(String name, KnowledgeBaseStatus status) {
        knowledgeBaseMapper.setStatus(name, status);
    }
    public ConversationService conversations() { return conversations; }
    public AnswerSessionService answerSessions() { return answerSessions; }

    /** 用给定的内容清理器构造级联删除推进器，便于按脚本观察每轮推进结果。 */
    public KnowledgeBaseCascadeDeleteTask cascadeDeleteTask(KnowledgeBaseContentCleaner cleaner) {
        return new KnowledgeBaseCascadeDeleteTask(knowledgeBaseMapper, cleaner);
    }
    public FeedbackService feedbacks() { return feedbacks; }
    public List<String> conversationAuditTrail() { return List.copyOf(conversationAuditTrail); }
    public List<String> feedbackAuditTrail() { return List.copyOf(feedbackAuditTrail); }
    /** 记录服务层触发内容重试清理的知识库，用于断言"重试删除"确实重新驱动了清理。 */
    public List<Long> retriedContentCleanups() { return List.copyOf(retriedContentCleanups); }

    /** 用自定义 RAG 端口构造一套独立的会话/回答装配，便于观察检索入参。 */
    public ConversationRig conversationsWith(RagAnswerPort rag) {
        DocumentService documents = Mockito.mock(DocumentService.class);
        Mockito.when(documents.hasAvailableDocuments(Mockito.anyLong())).thenReturn(true);
        Feedbacks feedbackMapper = new Feedbacks();
        QaRecords qaRecordMapper = new QaRecords(feedbackMapper);
        ConversationMapper conversationsMapper = new Conversations();
        Diagnostics diagnostics = new Diagnostics();
        QaRecordService records = new QaRecordServiceImpl(qaRecordMapper, diagnostics);
        return new ConversationRig(
                new ConversationServiceImpl(conversationsMapper, knowledgeBases, records,
                        new FeedbackServiceImpl(feedbackMapper,
                                new QaRecordEvidenceServiceImpl(qaRecordMapper, diagnostics),
                                recordingFeedbackAudit()),
                        (conversationId, removedRecordCount) -> { }),
                new AnswerSessionServiceImpl(conversationsMapper, knowledgeBases, documents, records, rag));
    }

    /** 一套共享会话 Mapper 的会话/回答装配，供跨用例场景同时使用两个公开 interface。 */
    public record ConversationRig(ConversationService conversations, AnswerSessionService answerSessions) { }

    /** 记录审计调用，便于断言管理端操作留痕且不含正文。 */
    private FeedbackAudit recordingFeedbackAudit() {
        return new FeedbackAudit() {
            @Override
            public void recordViewed(long feedbackId, long qaId) {
                feedbackAuditTrail.add("viewed:" + feedbackId + ":" + qaId);
            }

            @Override
            public void recordResolved(long feedbackId, String status) {
                feedbackAuditTrail.add("resolved:" + feedbackId + ":" + status);
            }

            @Override
            public void recordResolveFailed(long feedbackId, String errorMessage) {
                feedbackAuditTrail.add("resolveFailed:" + feedbackId + ":" + errorMessage);
            }
        };
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
        public List<KnowledgeBaseEntity> findByIds(java.util.Collection<Long> ids) {
            return ids.stream().map(values::get).filter(java.util.Objects::nonNull).toList();
        }
        public int updateStatusIfCurrentStatus(long id, String newStatus, String errorMessage, String currentStatus) {
            KnowledgeBaseEntity value = values.get(id);
            if (value == null || !value.getStatus().name().equals(currentStatus)) { return 0; }
            value.setStatus(KnowledgeBaseStatus.valueOf(newStatus));
            value.setErrorMessage(errorMessage);
            return 1;
        }
        public int startCleanupIfCurrentStatus(long id, String currentStatus) {
            KnowledgeBaseEntity value = values.get(id);
            if (value == null || !value.getStatus().name().equals(currentStatus)) { return 0; }
            value.setStatus(KnowledgeBaseStatus.DELETING);
            value.setErrorMessage("");
            // 镜像 SQL：进入 DELETING 的同时归零清理预算，重试才能真正重新开始
            value.setCleanupAttempt(0);
            return 1;
        }
        public int incrementCleanupAttempt(long id) {
            KnowledgeBaseEntity value = values.get(id);
            if (value == null || value.getStatus() != KnowledgeBaseStatus.DELETING) { return 0; }
            value.setCleanupAttempt((value.getCleanupAttempt() == null ? 0 : value.getCleanupAttempt()) + 1);
            return 1;
        }
        public int deleteByIdIfDeleting(long id) {
            KnowledgeBaseEntity value = values.get(id);
            if (value == null || value.getStatus() != KnowledgeBaseStatus.DELETING) { return 0; }
            values.remove(id);
            return 1;
        }
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

        private List<FeedbackEntity> matching(AdminFeedbackQuery query) {
            return byQaId.values().stream()
                    .filter(value -> query.status() == null || value.getStatus() == query.status())
                    .sorted(Comparator.comparing(FeedbackEntity::getId).reversed())
                    .toList();
        }

        public List<FeedbackEntity> findForAdmin(AdminFeedbackQuery query) {
            return matching(query).stream()
                    .skip(query.offset())
                    .limit(query.pageSize())
                    .toList();
        }

        public long countForAdmin(AdminFeedbackQuery query) { return matching(query).size(); }

        public AdminFeedbackDetailRow findDetailForAdmin(long id) {
            FeedbackEntity feedback = byQaId.values().stream()
                    .filter(value -> value.getId() == id)
                    .findFirst()
                    .orElse(null);
            if (feedback == null) {
                return null;
            }
            // 只返回反馈自身字段；问答正文由 qarecord 证据读取 seam 提供。
            AdminFeedbackDetailRow row = new AdminFeedbackDetailRow();
            row.setId(feedback.getId());
            row.setQaId(feedback.getQaId());
            row.setCategory(feedback.getCategory());
            row.setComment(feedback.getComment());
            row.setStatus(feedback.getStatus());
            row.setAdminNote(feedback.getAdminNote());
            row.setCreatedAt(feedback.getCreatedAt());
            row.setUpdatedAt(feedback.getUpdatedAt());
            return row;
        }

        public int updateStatus(long id, FeedbackStatus status, String adminNote) {
            FeedbackEntity feedback = byQaId.values().stream()
                    .filter(value -> value.getId() == id)
                    .findFirst()
                    .orElse(null);
            if (feedback == null) {
                return 0;
            }
            feedback.setStatus(status);
            feedback.setAdminNote(adminNote);
            feedback.setUpdatedAt(LocalDateTime.now());
            return 1;
        }
    }

    private static final class Diagnostics implements RetrievalDiagnosticMapper {
        private final Map<Long, List<RetrievalDiagnosticEntity>> byQaRecordId = new LinkedHashMap<>();

        void store(long qaRecordId, RetrievalDiagnosticEntity entity) {
            byQaRecordId.computeIfAbsent(qaRecordId, k -> new ArrayList<>()).add(entity);
        }

        @Override
        public int insertBatch(List<RetrievalDiagnosticEntity> items) {
            for (RetrievalDiagnosticEntity item : items) {
                if (item.getQaRecordId() != null) {
                    store(item.getQaRecordId(), item);
                }
            }
            return items.size();
        }

        @Override
        public List<RetrievalDiagnosticEntity> findByQaRecordId(long qaRecordId) {
            return byQaRecordId.getOrDefault(qaRecordId, List.of());
        }

        @Override
        public int deleteByConversation(long conversationId) {
            return 0;
        }
    }
}
