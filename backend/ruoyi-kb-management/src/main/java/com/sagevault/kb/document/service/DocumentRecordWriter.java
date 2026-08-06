package com.sagevault.kb.document.service;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 企业文档生命周期持久化 seam：唯一拥有文档与入库/清理任务的状态迁移、幂等、尝试次数和
 * FAILSAFE 裁决。跨能力调用只允许经过本能力公开的 {@code DocumentService}；本 interface 是
 * module 内部的持久化边界，供生命周期编排与 scheduler 复用同一套裁决。
 */
public interface DocumentRecordWriter {
    DocumentEntity create(UploadDocumentRequest request);

    void validateBatch(long knowledgeBaseId, List<MultipartFile> files);

    /** 创建首次入库任务（attempt 1），与文档记录创建分属独立短事务。 */
    IndexingTaskEntity createIndexingTask(DocumentEntity document);

    /** 显式重试：FAILED 翻转为 PROCESSING，并按最近任务递增 attempt，同事务创建新任务。 */
    IndexingTaskEntity beginRetry(long documentId);

    /** 清理重试：CLEANUP_FAILED/DELETING 收敛为 DELETING，递增清理尝试，同事务创建清理任务。 */
    IndexingTaskEntity beginCleanupRetry(long documentId);

    /** 删除 CAS：仅 AVAILABLE 可翻转为 DELETING；未命中返回 false，由调用方裁决冲突。 */
    boolean beginDelete(long documentId);

    /** 清理命令派发失败时回退：DELETING 翻回 AVAILABLE。 */
    void restoreAfterDeleteDispatchFailure(long documentId);

    /** 清理重试派发失败时补偿：DELETING 置为 CLEANUP_FAILED。 */
    void failCleanupRetry(long documentId, String errorMessage);

    /** 将任务标记为 FAILED，避免任务永久停留在 PROCESSING。 */
    void failTask(IndexingTaskEntity task, String errorMessage);

    /** 将文档标记为 FAILED。 */
    void failDocument(long documentId, String errorMessage);

    /**
     * FAILSAFE 裁决：扫描清理尝试达到阈值的 DELETING 文档并置为 CLEANUP_FAILED 终态。
     *
     * @param attemptThreshold 清理尝试次数阈值
     * @return 成功标记为 CLEANUP_FAILED 的文档数
     */
    int failStuckCleaning(int attemptThreshold);
}
