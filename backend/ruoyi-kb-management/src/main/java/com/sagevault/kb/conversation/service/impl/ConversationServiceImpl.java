package com.sagevault.kb.conversation.service.impl;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.service.QaRecordService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ConversationServiceImpl implements ConversationService {
    private final ConversationMapper mapper;
    private final KnowledgeBaseService knowledgeBases;
    private final QaRecordService records;
    private final RagAnswerPort rag;

    public ConversationServiceImpl(ConversationMapper mapper, KnowledgeBaseService knowledgeBases,
            QaRecordService records, RagAnswerPort rag) {
        this.mapper = mapper;
        this.knowledgeBases = knowledgeBases;
        this.records = records;
        this.rag = rag;
    }

    @Override
    public ConversationResponse create(long userId, CreateConversationRequest request) {
        knowledgeBases.requireAvailable(request.knowledgeBaseId());
        ConversationEntity entity = new ConversationEntity();
        entity.setUserId(userId);
        entity.setKnowledgeBaseId(request.knowledgeBaseId());
        mapper.insert(entity);
        return response(entity);
    }

    @Override
    public Flux<AnswerEvent> ask(long userId, long conversationId, AskQuestionRequest request) {
        if (request.question() == null || request.question().isBlank()
                || request.requestId() == null || request.requestId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "问题或请求标识不能为空");
        }
        ConversationEntity conversation = requireOwned(userId, conversationId);
        knowledgeBases.requireAvailable(conversation.getKnowledgeBaseId());
        String generationId = UUID.randomUUID().toString();
        try {
            records.create(conversationId, userId, request.requestId(), generationId, request.question());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "请求已处理", exception);
        }
        AtomicBoolean terminal = new AtomicBoolean();
        StringBuilder answer = new StringBuilder();
        return Flux.defer(() -> rag.answer(conversation.getKnowledgeBaseId(), request.question(), request.requestId(), generationId))
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
                    }
                })
                .doOnComplete(() -> markUnfinishedIfNeeded(generationId, terminal))
                .doOnError(error -> markUnfinishedIfNeeded(generationId, terminal));
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
        return new ConversationResponse(entity.getId(), entity.getUserId(), entity.getKnowledgeBaseId());
    }

    private void markUnfinishedIfNeeded(String generationId, AtomicBoolean terminal) {
        if (terminal.compareAndSet(false, true)) {
            records.markUnfinished(generationId);
        }
    }
}
