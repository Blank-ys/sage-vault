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
 * 协调企业文档重试的原子状态转换与任务创建。
 *
 * 业务状态变更（FAILED→PROCESSING）与持久化任务创建必须在同一本地事务提交，
 * 提交后才由 {@link DocumentServiceImpl} 派发外部 HTTP 命令。
 */
@Component
public class RetryRecordWriter {
    private final DocumentMapper documentMapper;
    private final IndexingTaskMapper indexingTaskMapper;

    public RetryRecordWriter(DocumentMapper documentMapper, IndexingTaskMapper indexingTaskMapper) {
        this.documentMapper = documentMapper;
        this.indexingTaskMapper = indexingTaskMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public IndexingTaskEntity beginRetry(long documentId) {
        DocumentEntity document = documentMapper.findById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        int updated = documentMapper.updateStatusIfCurrentStatus(documentId,
                DocumentStatus.PROCESSING.name(), "", DocumentStatus.FAILED.name());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT,
                    "仅处理失败的文档可以重试");
        }
        document.setStatus(DocumentStatus.PROCESSING);
        document.setErrorMessage("");
        IndexingTaskEntity latest = indexingTaskMapper.findLatestByDocumentId(documentId);
        int nextAttempt = (latest == null ? 0 : latest.getAttempt()) + 1;
        IndexingTaskEntity task = new IndexingTaskEntity();
        task.setDocumentId(documentId);
        task.setTaskId(UUID.randomUUID().toString());
        task.setAttempt(nextAttempt);
        task.setStatus(IndexingTaskStatus.PROCESSING);
        task.setErrorMessage("");
        indexingTaskMapper.insert(task);
        return task;
    }

    /**
     * 在派发失败时将任务标记为 FAILED，避免任务永久停留在 PROCESSING。
     *
     * 与 {@link #beginRetry} 分属独立事务：beginRetry 提交后才派发外部 HTTP 命令，
     * 派发失败后由此方法补偿任务终态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void failTask(IndexingTaskEntity task, String errorMessage) {
        indexingTaskMapper.updateTerminalState(task.getTaskId(), task.getAttempt(),
                IndexingTaskStatus.FAILED.name(), errorMessage, LocalDateTime.now());
    }
}
