package com.sagevault.kb.qarecord.service.impl;

import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.service.QaRecordService;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QaRecordServiceImpl implements QaRecordService {
    private final QaRecordMapper mapper;

    public QaRecordServiceImpl(QaRecordMapper mapper) {
        this.mapper = mapper;
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
    public void markUnfinished(String generationId) {
        decideTerminalState(generationId, QaRecordStatus.UNFINISHED, "");
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
        return mapper.deleteByConversation(conversationId);
    }

    private static QaRecordResponse response(QaRecordEntity entity) {
        return new QaRecordResponse(entity.getId(), entity.getConversationId(), entity.getGenerationId(),
                entity.getQuestion(), entity.getAnswer(), entity.getStatus(), entity.getCreatedAt());
    }

    private void decideTerminalState(String generationId, QaRecordStatus target, String answer) {
        if (mapper.updateTerminalState(generationId, target, answer) == 1) {
            return;
        }
        QaRecordEntity record = mapper.findByGenerationId(generationId);
        if (record == null) {
            throw new BusinessException(ErrorCode.QA_RECORD_NOT_FOUND, "问答记录不存在");
        }
        if (record.getStatus() != target) {
            throw new BusinessException(ErrorCode.QA_RECORD_STATE_CONFLICT, "问答记录状态冲突");
        }
    }
}
