package com.sagevault.kb.knowledgebase.mapper;

import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import java.util.List;

public interface KnowledgeBaseMapper {
    int insert(KnowledgeBaseEntity entity);
    int update(KnowledgeBaseEntity entity);
    KnowledgeBaseEntity findById(long id);
    KnowledgeBaseEntity findByNormalizedName(String normalizedName);
    List<KnowledgeBaseEntity> findAll();
    List<KnowledgeBaseEntity> findByStatus(String status);
}
