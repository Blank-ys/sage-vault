package com.sagevault.kb.document.mapper;

import com.sagevault.kb.document.domain.DocumentEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DocumentMapper {
    int insert(DocumentEntity entity);

    int updateStatus(@Param("id") long id, @Param("status") String status,
            @Param("errorMessage") String errorMessage);

    int updateStatusIfCurrentStatus(@Param("id") long id, @Param("newStatus") String newStatus,
            @Param("errorMessage") String errorMessage, @Param("currentStatus") String currentStatus);

    DocumentEntity findById(long id);

    DocumentEntity findByKbIdAndNormalizedName(@Param("kbId") long kbId,
            @Param("normalizedName") String normalizedName);

    List<DocumentEntity> findByKbIdAndNormalizedNames(@Param("kbId") long kbId,
            @Param("normalizedNames") Collection<String> normalizedNames);

    List<DocumentEntity> findByKbId(long kbId);

    int countAvailableByKbId(@Param("kbId") long kbId);

    int deleteById(long id);

    /** 幂等更新文档状态，仅在指定状态时生效 */
    int updateStatusIdempotent(@Param("id") long id, @Param("status") String status,
            @Param("errorMessage") String errorMessage);

    /** 递增清理尝试次数并将 CLEANUP_FAILED 翻转为 DELETING，仅在 CLEANUP_FAILED 状态下生效 */
    int incrementCleanupAttempt(@Param("id") long id, @Param("newAttempt") int newAttempt,
            @Param("phase") String phase);

    /** 在 DELETING 状态下递增清理尝试次数（幂等重派场景），仅当仍为 DELETING 时生效 */
    int incrementCleanupAttemptWhileDeleting(@Param("id") long id, @Param("newAttempt") int newAttempt);

    /**
     * 残留检测 FAILSAFE：扫描已进入清理阶段、仍处于 DELETING 且清理尝试次数达到阈值的文档。
     * @param threshold 清理尝试次数阈值（cleanup_attempt >= threshold 视为残留）
     */
    List<DocumentEntity> findStuckCleaningDocuments(@Param("threshold") int threshold);

    /** 幂等将残留的 DELETING 文档置为 CLEANUP_FAILED 终态，仅当仍为 DELETING 时生效 */
    int markCleanupFailed(@Param("id") long id, @Param("message") String message);
}
