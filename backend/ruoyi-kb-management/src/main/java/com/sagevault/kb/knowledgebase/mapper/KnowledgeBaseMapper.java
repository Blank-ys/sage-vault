package com.sagevault.kb.knowledgebase.mapper;

import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface KnowledgeBaseMapper {
    int insert(KnowledgeBaseEntity entity);
    int update(KnowledgeBaseEntity entity);
    KnowledgeBaseEntity findById(long id);
    KnowledgeBaseEntity findByNormalizedName(String normalizedName);
    List<KnowledgeBaseEntity> findAll();
    List<KnowledgeBaseEntity> findByStatus(String status);

    /**
     * CAS 状态流转：仅当知识库仍处于 {@code currentStatus} 时才写入 {@code newStatus}。
     * 返回 0 表示状态已被其他请求推进，调用方据此判定幂等或冲突。
     */
    int updateStatusIfCurrentStatus(@Param("id") long id, @Param("newStatus") String newStatus,
            @Param("errorMessage") String errorMessage, @Param("currentStatus") String currentStatus);

    /** 递增级联清理尝试次数，仅在知识库仍处于 DELETING 时生效。 */
    int incrementCleanupAttempt(@Param("id") long id);

    /** 删除知识库活动记录，仅当知识库仍处于 DELETING 时生效。 */
    int deleteByIdIfDeleting(long id);

    /** 批量查询仍存在活动记录的知识库；结果中缺失的 ID 表示已被删除。 */
    List<KnowledgeBaseEntity> findByIds(@Param("ids") Collection<Long> ids);
}
