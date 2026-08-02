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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseServiceImpl.class);

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
        KnowledgeBaseEntity entity = mapper.findById(knowledgeBaseId);
        if (entity == null) {
            // 活动记录已被级联删除成功移除：历史仍可读，但不接受任何新写入
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETED, "知识库已删除");
        }
        if (entity.getStatus() != KnowledgeBaseStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE, "知识库当前不可用");
        }
    }

    @Override
    public KnowledgeBaseResponse delete(long id) {
        KnowledgeBaseEntity entity = requireEntity(id);

        // 幂等：已在删除中的知识库重复删除直接返回当前状态，不重复派发清理
        if (entity.getStatus() == KnowledgeBaseStatus.DELETING) {
            log.info("Knowledge base {} is already in DELETING status, delete is idempotent", id);
            return response(entity);
        }

        // 删除失败的知识库允许重新发起删除，由后台清理重新推进
        KnowledgeBaseStatus from = entity.getStatus();
        if (from != KnowledgeBaseStatus.AVAILABLE && from != KnowledgeBaseStatus.DELETE_FAILED) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_STATE_CONFLICT, "知识库当前状态不允许删除");
        }

        int updated = mapper.updateStatusIfCurrentStatus(id, KnowledgeBaseStatus.DELETING.name(), "", from.name());
        if (updated == 0) {
            // 并发删除：另一请求已推进状态，按幂等处理返回最新状态
            log.info("Knowledge base {} status changed concurrently during delete, returning latest state", id);
            return response(requireEntity(id));
        }
        entity.setStatus(KnowledgeBaseStatus.DELETING);
        entity.setErrorMessage("");
        audit.record(ManagementAudit.Operation.DELETE, id);
        return response(entity);
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

    @Override
    public Map<Long, String> resolveNames(Set<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findByIds(knowledgeBaseIds).stream()
                .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName));
    }

    private KnowledgeBaseResponse response(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseResponse(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getStatus(), entity.getErrorMessage() == null ? "" : entity.getErrorMessage());
    }

    private static BusinessException nameConflict() {
        return new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT, "知识库名称已存在");
    }

}
