package com.sagevault.kb.knowledgebase.service.impl;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseResponse;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.domain.UpdateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.mapper.KnowledgeBaseMapper;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.knowledgebase.service.port.ManagementAudit;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
    private final KnowledgeBaseMapper mapper;
    private final ManagementAudit audit;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper mapper, ManagementAudit audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        String name = requiredName(request.name());
        ensureUnique(name, null);
        KnowledgeBaseEntity entity = entity(0, name, request.description(), KnowledgeBaseStatus.AVAILABLE);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        KnowledgeBaseResponse response = response(entity);
        recordAfterCommit(ManagementAudit.Operation.CREATE, response.id());
        return response;
    }

    @Override
    public KnowledgeBaseResponse get(long id) {
        KnowledgeBaseEntity entity = mapper.findById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE, "知识库不存在");
        }
        return response(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseResponse update(long id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBaseEntity current = requireEntity(id);
        String name = requiredName(request.name());
        ensureUnique(name, id);
        KnowledgeBaseEntity entity = entity(id, name, request.description(), current.getStatus());
        try {
            mapper.update(entity);
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        KnowledgeBaseResponse response = response(entity);
        recordAfterCommit(ManagementAudit.Operation.UPDATE, response.id());
        return response;
    }

    @Override
    public List<KnowledgeBaseResponse> listAll() {
        return mapper.findAll().stream().map(this::response).toList();
    }

    @Override
    public List<KnowledgeBaseResponse> listAvailable() {
        return mapper.findByStatus(KnowledgeBaseStatus.AVAILABLE.name()).stream().map(this::response).toList();
    }

    @Override
    public void requireAvailable(long knowledgeBaseId) {
        if (requireEntity(knowledgeBaseId).getStatus() != KnowledgeBaseStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE, "知识库当前不可用");
        }
    }

    private KnowledgeBaseEntity requireEntity(long id) {
        KnowledgeBaseEntity entity = mapper.findById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE, "知识库不存在");
        }
        return entity;
    }

    private void ensureUnique(String name, Long currentId) {
        KnowledgeBaseEntity existing = mapper.findByNormalizedName(normalize(name));
        if (existing != null && (currentId == null || !existing.getId().equals(currentId))) {
            throw nameConflict();
        }
    }

    private static KnowledgeBaseEntity entity(long id, String name, String description, KnowledgeBaseStatus status) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id == 0 ? null : id);
        entity.setName(name);
        entity.setNormalizedName(normalize(name));
        entity.setDescription(description == null ? "" : description.trim());
        entity.setStatus(status);
        return entity;
    }

    private KnowledgeBaseResponse response(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseResponse(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getStatus());
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "知识库名称不能为空");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static BusinessException nameConflict() {
        return new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT, "知识库名称已存在");
    }

    private void recordAfterCommit(ManagementAudit.Operation operation, long knowledgeBaseId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            audit.record(operation, knowledgeBaseId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                audit.record(operation, knowledgeBaseId);
            }
        });
    }
}
