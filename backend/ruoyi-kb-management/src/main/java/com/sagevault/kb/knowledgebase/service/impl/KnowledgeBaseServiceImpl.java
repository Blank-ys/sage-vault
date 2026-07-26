package com.sagevault.kb.knowledgebase.service.impl;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseName;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseResponse;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.domain.UpdateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.mapper.KnowledgeBaseMapper;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.knowledgebase.service.port.ManagementAudit;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
    private final KnowledgeBaseMapper mapper;
    private final ManagementAudit audit;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper mapper, ManagementAudit audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    @Override
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        KnowledgeBaseName name = KnowledgeBaseName.of(request.name());
        ensureUnique(name, null);
        KnowledgeBaseEntity entity = entity(0, name.value(), request.description(), KnowledgeBaseStatus.AVAILABLE);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        KnowledgeBaseResponse response = response(entity);
        audit.record(ManagementAudit.Operation.CREATE, response.id());
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
    public KnowledgeBaseResponse update(long id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBaseEntity current = requireEntity(id);
        KnowledgeBaseName name = KnowledgeBaseName.of(request.name());
        ensureUnique(name, id);
        KnowledgeBaseEntity entity = entity(id, name.value(), request.description(), current.getStatus());
        try {
            mapper.update(entity);
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        KnowledgeBaseResponse response = response(entity);
        audit.record(ManagementAudit.Operation.UPDATE, response.id());
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

    private void ensureUnique(KnowledgeBaseName name, Long currentId) {
        KnowledgeBaseEntity existing = mapper.findByNormalizedName(name.normalizedValue());
        if (existing != null && (currentId == null || !existing.getId().equals(currentId))) {
            throw nameConflict();
        }
    }

    private static KnowledgeBaseEntity entity(long id, String name, String description, KnowledgeBaseStatus status) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id == 0 ? null : id);
        entity.setName(name);
        entity.setNormalizedName(KnowledgeBaseName.of(name).normalizedValue());
        entity.setDescription(description == null ? "" : description.trim());
        entity.setStatus(status);
        return entity;
    }

    private KnowledgeBaseResponse response(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseResponse(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getStatus());
    }

    private static BusinessException nameConflict() {
        return new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT, "知识库名称已存在");
    }

}
