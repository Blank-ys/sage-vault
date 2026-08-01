package com.sagevault.kb.qarecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.service.impl.QaRecordServiceImpl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QaRecordServiceTest {
    @Test
    void createsAStartedRecordAndDecidesRefusalOnce() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records);

        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.markRefused("generation-1", "no matching document");
        service.markRefused("generation-1", "no matching document");

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.REFUSED);
        assertThat(record.getAnswer()).isEqualTo("no matching document");
    }

    @Test
    void rejectsLateDifferentTerminalStateWithoutOverwritingTheRecord() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records);
        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.markRefused("generation-1", "no matching document");

        assertThatThrownBy(() -> service.markUnfinished("generation-1"))
                .isInstanceOf(BusinessException.class);

        assertThat(records.findByGenerationId("generation-1").getStatus())
                .isEqualTo(QaRecordStatus.REFUSED);
    }

    @Test
    void distinguishesAMissingRecordFromAConditionalUpdateMiss() {
        QaRecordService service = new QaRecordServiceImpl(new Records());

        assertThatThrownBy(() -> service.markUnfinished("missing-generation"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void appendsDeltasToStartedRecord() {
        Records records = new Records();
        QaRecordService service = new QaRecordServiceImpl(records);

        service.create(10L, 20L, "request-1", "generation-1", "question");
        service.appendAnswer("generation-1", "片段一");
        service.appendAnswer("generation-1", "片段二");
        service.markCompleted("generation-1", records.findByGenerationId("generation-1").getAnswer());

        QaRecordEntity record = records.findByGenerationId("generation-1");
        assertThat(record.getStatus()).isEqualTo(QaRecordStatus.COMPLETED);
        assertThat(record.getAnswer()).isEqualTo("片段一片段二");
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
        public QaRecordEntity findByGenerationId(String generationId) {
            return records.get(generationId);
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
}
