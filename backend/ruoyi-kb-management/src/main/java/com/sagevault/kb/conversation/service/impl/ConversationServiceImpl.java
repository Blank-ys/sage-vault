package com.sagevault.kb.conversation.service.impl;

import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.domain.RenameConversationRequest;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.conversation.service.port.ConversationAudit;
import com.sagevault.kb.feedback.domain.FeedbackResponse;
import com.sagevault.kb.feedback.service.FeedbackService;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import com.sagevault.kb.qarecord.service.QaRecordService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationServiceImpl implements ConversationService {
    private static final int TITLE_MAX_LENGTH = 200;

    private final ConversationMapper mapper;
    private final KnowledgeBaseService knowledgeBases;
    private final QaRecordService records;
    private final FeedbackService feedbacks;
    private final ConversationAudit audit;

    public ConversationServiceImpl(ConversationMapper mapper, KnowledgeBaseService knowledgeBases,
            QaRecordService records, FeedbackService feedbacks, ConversationAudit audit) {
        this.mapper = mapper;
        this.knowledgeBases = knowledgeBases;
        this.records = records;
        this.feedbacks = feedbacks;
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
        return responses(mapper.findByUser(userId));
    }

    @Override
    public ConversationResponse get(long userId, long conversationId) {
        return response(requireOwned(userId, conversationId));
    }

    @Override
    public List<QaRecordResponse> history(long userId, long conversationId) {
        requireOwned(userId, conversationId);
        List<QaRecordResponse> history = records.listByConversation(conversationId);
        // 历史里回显是否已反馈，让前端把入口收敛为已提交状态而不是允许重复提交。
        Map<Long, FeedbackResponse> submitted =
                feedbacks.findSubmitted(history.stream().map(QaRecordResponse::id).toList());
        return history.stream()
                .map(record -> record.withFeedbackSubmitted(submitted.containsKey(record.id())))
                .toList();
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

    /**
     * 会话视图带上知识库存续状态。知识库活动记录被级联删除后历史仍可读，
     * 前端据 {@code knowledgeBaseDeleted} 标记"知识库已删除"并禁用继续提问。
     */
    private ConversationResponse response(ConversationEntity entity) {
        return response(entity, knowledgeBases.resolveNames(Set.of(entity.getKnowledgeBaseId())));
    }

    private List<ConversationResponse> responses(List<ConversationEntity> entities) {
        Set<Long> knowledgeBaseIds = entities.stream()
                .map(ConversationEntity::getKnowledgeBaseId)
                .collect(Collectors.toSet());
        Map<Long, String> names = knowledgeBases.resolveNames(knowledgeBaseIds);
        return entities.stream().map(entity -> response(entity, names)).toList();
    }

    private static ConversationResponse response(ConversationEntity entity, Map<Long, String> knowledgeBaseNames) {
        String name = knowledgeBaseNames.get(entity.getKnowledgeBaseId());
        return new ConversationResponse(entity.getId(), entity.getUserId(), entity.getKnowledgeBaseId(),
                entity.getTitle() == null ? "" : entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt(),
                name == null, name == null ? "" : name);
    }
}
