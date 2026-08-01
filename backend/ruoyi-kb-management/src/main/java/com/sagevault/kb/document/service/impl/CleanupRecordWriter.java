package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.IndexingTaskStatus;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.mapper.IndexingTaskMapper;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协调清理重试的原子状态转换与任务创建。
 *
 * 文档从 CLEANUP_FAILED 回到 DELETING，创建新的清理任务记录，
 * 尝试次数递增，由 {@link DocumentServiceImpl} 派发外部清理命令。
 */
@Component
public class CleanupRecordWriter {
    private final DocumentMapper documentMapper;
    private final IndexingTaskMapper indexingTaskMapper;

    public CleanupRecordWriter(DocumentMapper documentMapper, IndexingTaskMapper indexingTaskMapper) {
        this.documentMapper = documentMapper;
        this.indexingTaskMapper = indexingTaskMapper;
    }

    /**
     * 原子地将文档恢复为 DELETING 并创建清理任务记录，用于清理重试（幂等）。
     *
     * <p>允许两种来源状态：
     * <ul>
     *   <li>{@code CLEANUP_FAILED}：终态回退，CAS 翻转为 DELETING 并保留诊断阶段；
     *   <li>{@code DELETING}：清理仍在进行中，幂等重派，不重置诊断阶段。
     * </ul>
     * 无论哪种来源，清理尝试次数（cleanup_attempt）都会递增，供残留检测 FAILSAFE 判断。
     *
     * @return 新的清理任务实体
     */
    @Transactional(rollbackFor = Exception.class)
    public IndexingTaskEntity beginCleanupRetry(long documentId) {
        DocumentEntity document = documentMapper.findById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        DocumentStatus status = document.getStatus();
        if (status != DocumentStatus.DELETING && status != DocumentStatus.CLEANUP_FAILED) {
            throw new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT,
                    "仅处于 DELETING 或 CLEANUP_FAILED 的文档可以重试清理");
        }

        // 基于最近一次清理任务推算下一次尝试序号
        IndexingTaskEntity latest = indexingTaskMapper.findLatestCleanupByDocumentId(documentId);
        int nextAttempt = (latest == null ? 0 : latest.getAttempt()) + 1;
        String phase = document.getCleanupPhase() == null ? "" : document.getCleanupPhase();

        int updated;
        if (status == DocumentStatus.CLEANUP_FAILED) {
            // CAS 更新：CLEANUP_FAILED → DELETING，递增尝试次数并保留诊断阶段
            updated = documentMapper.incrementCleanupAttempt(documentId, nextAttempt, phase);
        } else {
            // 已是 DELETING：幂等确认，并递增尝试次数（不重置诊断阶段）
            updated = documentMapper.incrementCleanupAttemptWhileDeleting(documentId, nextAttempt);
            if (updated == 0) {
                updated = documentMapper.updateStatusIdempotent(documentId, DocumentStatus.DELETING.name(), "");
            }
        }
        if (updated == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT,
                    "文档状态已变更，清理重试无法继续");
        }

        // 创建新的清理任务记录
        IndexingTaskEntity task = new IndexingTaskEntity();
        task.setDocumentId(documentId);
        task.setTaskId("cleanup-retry-" + UUID.randomUUID());
        task.setAttempt(nextAttempt);
        task.setStatus(IndexingTaskStatus.PROCESSING);
        task.setErrorMessage("");
        task.setTaskType("CLEANUP");
        indexingTaskMapper.insert(task);

        return task;
    }

    /**
     * 在清理派发失败时将任务标记为 FAILED。
     */
    @Transactional(rollbackFor = Exception.class)
    public void failTask(IndexingTaskEntity task, String errorMessage) {
        indexingTaskMapper.updateTerminalState(task.getTaskId(), task.getAttempt(),
                IndexingTaskStatus.FAILED.name(), errorMessage, LocalDateTime.now());
    }
}
