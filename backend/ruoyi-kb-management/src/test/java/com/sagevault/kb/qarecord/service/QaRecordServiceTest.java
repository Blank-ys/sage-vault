package com.sagevault.kb.qarecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.feedback.domain.RetrievedChunkDiagnostic;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.domain.RetrievalDiagnosticEntity;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.mapper.RetrievalDiagnosticMapper;
import com.sagevault.kb.qarecord.service.impl.QaRecordServiceImpl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QaRecordServiceTest {
    @Test
    void createsAStartedRecordAndDecidesRefusalOnce() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());

        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.markRefused("generation-1", "no matching document");
        service.markRefused("generation-1", "no matching document");

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.REFUSED);
        assertThat(record.getAnswer()).isEqualTo("no matching document");
    }

    @Test
    void failedKeepsPartialAnswerAndRecordsMaskedDetailAsTerminal() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.appendAnswer("generation-1", "已经生成的部分");

        service.markFailed("generation-1", "retrieval_or_generation_failed");

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.FAILED);
        // 残缺正文保留，文案为脱敏后的受控失败类别。
        assertThat(record.getAnswer()).isEqualTo("retrieval_or_generation_failed");
    }

    @Test
    void failedDoesNotOverwriteAnAlreadyDecidedTerminalRefusal() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.markRefused("generation-1", "no matching document");

        assertThat(service.markFailed("generation-1", "unexpected_failure")).isFalse();

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.REFUSED);
    }

    @Test
    void streamEndFallbackNeverOverwritesAnAlreadyDecidedTerminalState() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.markRefused("generation-1", "no matching document");

        service.markUnfinished("generation-1");

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.REFUSED);
        assertThat(record.getAnswer()).isEqualTo("no matching document");
    }

    @Test
    void stopKeepsThePartialAnswerAlreadyStreamedToTheUser() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.appendAnswer("generation-1", "已经生成的部分");

        assertThat(service.markStopped("generation-1")).isTrue();

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.STOPPED);
        assertThat(record.getAnswer()).isEqualTo("已经生成的部分");
    }

    @Test
    void onlyTheFirstStopWinsTheTerminalTransition() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());
        service.create(10L, 20L, "request-1", "generation-1", "question");

        assertThat(service.markStopped("generation-1")).isTrue();
        assertThat(service.markStopped("generation-1")).isFalse();
        assertThat(service.markStopped("missing-generation")).isFalse();
    }

    @Test
    void stopWinsOverTheConcurrentStreamEndFallback() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.appendAnswer("generation-1", "已经生成的部分");

        service.markStopped("generation-1");
        service.markUnfinished("generation-1");

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.STOPPED);
        assertThat(record.getAnswer()).isEqualTo("已经生成的部分");
    }

    @Test
    void unfinishedKeepsThePartialAnswerAlreadyStreamedToTheUser() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.appendAnswer("generation-1", "断线前的部分");

        service.markUnfinished("generation-1");

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.UNFINISHED);
        assertThat(record.getAnswer()).isEqualTo("断线前的部分");
    }

    @Test
    void distinguishesAMissingRecordFromAConditionalUpdateMiss() {
        QaRecordService service = new QaRecordServiceImpl(new Records(), new Diagnostics());

        assertThatThrownBy(() -> service.markUnfinished("missing-generation"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void appendsDeltasToStartedRecord() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records, new Diagnostics());

        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.appendAnswer("generation-1", "片段一");
        service.appendAnswer("generation-1", "片段二");
        service.markCompleted("generation-1", records.findByGenerationId("generation-1").getAnswer());

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.COMPLETED);
        assertThat(record.getAnswer()).isEqualTo("片段一片段二");
    }

    @Test
    void savesRetrievalAndStageDiagnosticsForCompletedAnswer() {
        Records records = new Records();
        Diagnostics diagnostics = new Diagnostics();
        QaRecordService service = new QaRecordServiceImpl(records, diagnostics);
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.markCompleted("generation-1", "答案正文");

        service.saveDiagnostics("generation-1",
                List.of(new RetrievedChunkDiagnostic("doc-1", "doc-1#c1", 0.32)),
                Map.of("embedding", 8, "retrieval", 14, "generation", 326));

        // 检索片段诊断只含标识与分数，不含正文；阶段耗时按 key 落库。
        assertThat(diagnostics.items()).anyMatch(d ->
                "retrieval".equals(d.getStage()) && "doc-1".equals(d.getDocumentId())
                        && "doc-1#c1".equals(d.getChunkId()) && 0.32 == d.getScore());
        assertThat(diagnostics.items()).extracting(RetrievalDiagnosticEntity::getStage)
                .contains("embedding", "retrieval", "generation");
    }

    @Test
    void saveDiagnosticsForUnknownGenerationIsSilentlySkipped() {
        Records records = new Records();
        Diagnostics diagnostics = new Diagnostics();
        QaRecordService service = new QaRecordServiceImpl(records, diagnostics);

        service.saveDiagnostics("missing-generation", List.of(), Map.of());

        assertThat(diagnostics.items()).isEmpty();
    }

    private static final class Records implements QaRecordMapper {
        private final Map<String, QaRecordEntity> records = new LinkedHashMap<>();

        @Override
        public int insert(QaRecordEntity entity) {
            records.put(entity.getGenerationId(), entity);
            return 1;
        }

        @Override
        public int appendAnswer(String generationId, String delta) {
            QaRecordEntity entity = records.get(generationId);
            if (entity == null || entity.getStatus() != QaRecordStatus.STARTED) {
                return 0;
            }
            entity.setAnswer(entity.getAnswer() + delta);
            return 1;
        }

        @Override
        public int updateTerminalState(String generationId, QaRecordStatus status, String answer) {
            QaRecordEntity entity = records.get(generationId);
            if (entity == null || entity.getStatus() != QaRecordStatus.STARTED) {
                return 0;
            }
            entity.setStatus(status);
            entity.setAnswer(answer);
            return 1;
        }

        @Override
        public int updateTerminalStatusKeepingAnswer(String generationId, QaRecordStatus status) {
            QaRecordEntity entity = records.get(generationId);
            if (entity == null || entity.getStatus() != QaRecordStatus.STARTED) {
                return 0;
            }
            entity.setStatus(status);
            return 1;
        }

        @Override
        public QaRecordEntity findByGenerationId(String generationId) {
            return records.get(generationId);
        }

        @Override
        public QaRecordEntity findById(long id) {
            return records.values().stream()
                    .filter(entity -> entity.getId() != null && entity.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<QaRecordEntity> findByConversation(long conversationId) {
            return records.values().stream()
                    .filter(entity -> entity.getConversationId() == conversationId)
                    .toList();
        }

        @Override
        public int countByConversation(long conversationId) {
            return findByConversation(conversationId).size();
        }

        @Override
        public int countPendingByConversation(long conversationId) {
            return (int) findByConversation(conversationId).stream()
                    .filter(entity -> entity.getStatus() == QaRecordStatus.STARTED)
                    .count();
        }

        @Override
        public int deleteByConversation(long conversationId) {
            List<QaRecordEntity> removed = findByConversation(conversationId);
            removed.forEach(entity -> records.remove(entity.getGenerationId()));
            return removed.size();
        }
    }

    private static final class Diagnostics implements RetrievalDiagnosticMapper {
        private final List<RetrievalDiagnosticEntity> stored = new ArrayList<>();

        List<RetrievalDiagnosticEntity> items() {
            return stored;
        }

        @Override
        public int insertBatch(List<RetrievalDiagnosticEntity> items) {
            stored.addAll(items);
            return items.size();
        }

        @Override
        public List<RetrievalDiagnosticEntity> findByQaRecordId(long qaRecordId) {
            return stored.stream()
                    .filter(entity -> entity.getQaRecordId() != null
                            && entity.getQaRecordId() == qaRecordId)
                    .toList();
        }

        @Override
        public int deleteByConversation(long conversationId) {
            return stored.size();
        }
    }
}
