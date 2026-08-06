package com.sagevault.kb.qarecord.service.impl;

import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.domain.RetrievalDiagnosticEntity;
import com.sagevault.kb.qarecord.domain.RetrievedChunkDiagnostic;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.mapper.RetrievalDiagnosticMapper;
import com.sagevault.kb.qarecord.service.QaRecordService;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QaRecordServiceImpl implements QaRecordService {
    private final QaRecordMapper mapper;
    private final RetrievalDiagnosticMapper diagnostics;

    public QaRecordServiceImpl(QaRecordMapper mapper, RetrievalDiagnosticMapper diagnostics) {
        this.mapper = mapper;
        this.diagnostics = diagnostics;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(long conversationId, long userId, String requestId, String generationId, String question) {
        QaRecordEntity entity = new QaRecordEntity();
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setRequestId(requestId);
        entity.setGenerationId(generationId);
        entity.setQuestion(question);
        entity.setAnswer("");
        entity.setStatus(QaRecordStatus.STARTED);
        mapper.insert(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendAnswer(String generationId, String delta) {
        if (mapper.appendAnswer(generationId, delta) == 1) {
            return;
        }
        QaRecordEntity record = mapper.findByGenerationId(generationId);
        if (record == null) {
            throw new BusinessException(ErrorCode.QA_RECORD_NOT_FOUND, "问答记录不存在");
        }
        if (record.getStatus() != QaRecordStatus.STARTED) {
            throw new BusinessException(ErrorCode.QA_RECORD_STATE_CONFLICT, "问答记录状态冲突");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markCompleted(String generationId, String answer) {
        decideTerminalState(generationId, QaRecordStatus.COMPLETED, answer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRefused(String generationId, String answer) {
        decideTerminalState(generationId, QaRecordStatus.REFUSED, answer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(String generationId, String detail) {
        // detail 是脱敏后的受控失败类别，作为可回显的终态文案落库；不写入原始异常或知识库 id。
        if (mapper.updateTerminalState(generationId, QaRecordStatus.FAILED, detail) == 1) {
            return true;
        }
        QaRecordEntity record = mapper.findByGenerationId(generationId);
        if (record == null) {
            throw new BusinessException(ErrorCode.QA_RECORD_NOT_FOUND, "问答记录不存在");
        }
        // 已处于其他终态（REFUSED/STOPPED/FAILED/UNFINISHED）：幂等返回 false，不覆盖。
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markUnfinished(String generationId) {
        if (mapper.updateTerminalStatusKeepingAnswer(generationId, QaRecordStatus.UNFINISHED) == 1) {
            return;
        }
        // 兜底裁决与显式停止可能并发：已有终态即视为已裁决，不覆盖也不报错。
        QaRecordEntity record = mapper.findByGenerationId(generationId);
        if (record == null) {
            throw new BusinessException(ErrorCode.QA_RECORD_NOT_FOUND, "问答记录不存在");
        }
        if (record.getStatus() == QaRecordStatus.STARTED) {
            throw new BusinessException(ErrorCode.QA_RECORD_STATE_CONFLICT, "问答记录状态冲突");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markStopped(String generationId) {
        return mapper.updateTerminalStatusKeepingAnswer(generationId, QaRecordStatus.STOPPED) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDiagnostics(String generationId, List<RetrievedChunkDiagnostic> retrievalDiagnostics,
            Map<String, Integer> stageDurations) {
        QaRecordEntity record = mapper.findByGenerationId(generationId);
        if (record == null) {
            // 防御：generationId 不存在时不落库，也不抛异常，避免污染终态裁决链路。
            return;
        }
        List<RetrievalDiagnosticEntity> items = new java.util.ArrayList<>();
        // 检索片段诊断：每个召回片段一条记录，只含标识与分数，不含正文。
        if (retrievalDiagnostics != null) {
            for (RetrievedChunkDiagnostic diag : retrievalDiagnostics) {
                RetrievalDiagnosticEntity entity = new RetrievalDiagnosticEntity();
                entity.setQaRecordId(record.getId());
                entity.setGenerationId(generationId);
                entity.setDocumentId(diag.documentId());
                entity.setChunkId(diag.chunkId());
                entity.setScore(diag.score());
                entity.setStage("retrieval");
                items.add(entity);
            }
        }
        // 阶段耗时：embedding / retrieval / generation 各一条记录，便于管理端统一展示。
        if (stageDurations != null) {
            for (Map.Entry<String, Integer> entry : stageDurations.entrySet()) {
                RetrievalDiagnosticEntity entity = new RetrievalDiagnosticEntity();
                entity.setQaRecordId(record.getId());
                entity.setGenerationId(generationId);
                entity.setStage(entry.getKey());
                entity.setDurationMs(entry.getValue().longValue());
                items.add(entity);
            }
        }
        if (!items.isEmpty()) {
            diagnostics.insertBatch(items);
        }
    }

    @Override
    public List<QaRecordResponse> listByConversation(long conversationId) {
        return mapper.findByConversation(conversationId).stream().map(QaRecordServiceImpl::response).toList();
    }

    @Override
    public boolean hasRecords(long conversationId) {
        return mapper.countByConversation(conversationId) > 0;
    }

    @Override
    public boolean hasPending(long conversationId) {
        return mapper.countPendingByConversation(conversationId) > 0;
    }

    @Override
    @Nullable
    public QaRecordEntity findByGenerationId(String generationId) {
        return mapper.findByGenerationId(generationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteByConversation(long conversationId) {
        diagnostics.deleteByConversation(conversationId);
        return mapper.deleteByConversation(conversationId);
    }

    private static QaRecordResponse response(QaRecordEntity entity) {
        return new QaRecordResponse(entity.getId(), entity.getConversationId(), entity.getGenerationId(),
                entity.getQuestion(), entity.getAnswer(), entity.getStatus(), entity.getCreatedAt(), false);
    }

    private boolean decideTerminalState(String generationId, QaRecordStatus target, String answer) {
        if (mapper.updateTerminalState(generationId, target, answer) == 1) {
            return true;
        }
        requireTerminalState(generationId, target);
        return false;
    }

    private void requireTerminalState(String generationId, QaRecordStatus target) {
        QaRecordEntity record = mapper.findByGenerationId(generationId);
        if (record == null) {
            throw new BusinessException(ErrorCode.QA_RECORD_NOT_FOUND, "问答记录不存在");
        }
        if (record.getStatus() != target) {
            throw new BusinessException(ErrorCode.QA_RECORD_STATE_CONFLICT, "问答记录状态冲突");
        }
    }
}
