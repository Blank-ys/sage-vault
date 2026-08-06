package com.sagevault.kb.qarecord.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordEvidence;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.domain.RetrievalDiagnosticEntity;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.mapper.RetrievalDiagnosticMapper;
import com.sagevault.kb.qarecord.service.impl.QaRecordEvidenceServiceImpl;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** qarecord 证据读取 seam 的装配行为：正文快照与诊断拆分。 */
class QaRecordEvidenceServiceTest {

    @Test
    void returnsTheAuthorizedSnapshotWithAssembledDiagnostics() {
        Records records = new Records();
        records.insert(record(10L, "request-1", "question", "answer", QaRecordStatus.COMPLETED));
        Diagnostics diagnostics = new Diagnostics();
        diagnostics.store(retrieval("doc-1", "doc-1#c1", 0.32));
        diagnostics.store(stage("embedding", 8L));
        QaRecordEvidenceService service = new QaRecordEvidenceServiceImpl(records, diagnostics);

        Optional<QaRecordEvidence> evidence = service.findEvidence(records.lastId());

        assertThat(evidence).isPresent();
        QaRecordEvidence value = evidence.get();
        assertThat(value.userId()).isEqualTo(10L);
        assertThat(value.requestId()).isEqualTo("request-1");
        assertThat(value.question()).isEqualTo("question");
        assertThat(value.answer()).isEqualTo("answer");
        assertThat(value.answerStatus()).isEqualTo(QaRecordStatus.COMPLETED);
        // 检索片段只携带标识与分数，阶段耗时按 key 装配
        assertThat(value.retrievalDiagnostics()).singleElement()
                .satisfies(chunk -> {
                    assertThat(chunk.documentId()).isEqualTo("doc-1");
                    assertThat(chunk.chunkId()).isEqualTo("doc-1#c1");
                    assertThat(chunk.score()).isEqualTo(0.32d);
                });
        assertThat(value.stageDurations()).isEqualTo(Map.of("embedding", 8L));
    }

    @Test
    void returnsEmptyForAnUnknownRecord() {
        QaRecordEvidenceService service =
                new QaRecordEvidenceServiceImpl(new Records(), new Diagnostics());

        assertThat(service.findEvidence(999L)).isEmpty();
    }

    private static QaRecordEntity record(long userId, String requestId, String question,
            String answer, QaRecordStatus status) {
        QaRecordEntity entity = new QaRecordEntity();
        entity.setUserId(userId);
        entity.setRequestId(requestId);
        entity.setQuestion(question);
        entity.setAnswer(answer);
        entity.setStatus(status);
        return entity;
    }

    private static RetrievalDiagnosticEntity retrieval(String documentId, String chunkId, double score) {
        RetrievalDiagnosticEntity entity = new RetrievalDiagnosticEntity();
        entity.setDocumentId(documentId);
        entity.setChunkId(chunkId);
        entity.setScore(score);
        entity.setStage("retrieval");
        return entity;
    }

    private static RetrievalDiagnosticEntity stage(String stage, long durationMs) {
        RetrievalDiagnosticEntity entity = new RetrievalDiagnosticEntity();
        entity.setStage(stage);
        entity.setDurationMs(durationMs);
        return entity;
    }

    private static final class Records implements QaRecordMapper {
        private final Map<Long, QaRecordEntity> byId = new java.util.LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        long lastId() {
            return ids.get();
        }

        @Override
        public int insert(QaRecordEntity entity) {
            entity.setId(ids.incrementAndGet());
            byId.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public QaRecordEntity findById(long id) {
            return byId.get(id);
        }

        @Override
        public int appendAnswer(String generationId, String delta) {
            return 0;
        }

        @Override
        public int updateTerminalState(String generationId, QaRecordStatus status, String answer) {
            return 0;
        }

        @Override
        public int updateTerminalStatusKeepingAnswer(String generationId, QaRecordStatus status) {
            return 0;
        }

        @Override
        public QaRecordEntity findByGenerationId(String generationId) {
            return null;
        }

        @Override
        public int countPendingByConversation(long conversationId) {
            return 0;
        }

        @Override
        public List<QaRecordEntity> findByConversation(long conversationId) {
            return List.of();
        }

        @Override
        public int countByConversation(long conversationId) {
            return 0;
        }

        @Override
        public int deleteByConversation(long conversationId) {
            return 0;
        }
    }

    private static final class Diagnostics implements RetrievalDiagnosticMapper {
        private final List<RetrievalDiagnosticEntity> stored = new java.util.ArrayList<>();

        void store(RetrievalDiagnosticEntity entity) {
            stored.add(entity);
        }

        @Override
        public int insertBatch(List<RetrievalDiagnosticEntity> items) {
            stored.addAll(items);
            return items.size();
        }

        @Override
        public List<RetrievalDiagnosticEntity> findByQaRecordId(long qaRecordId) {
            return stored;
        }

        @Override
        public int deleteByConversation(long conversationId) {
            return 0;
        }
    }
}
