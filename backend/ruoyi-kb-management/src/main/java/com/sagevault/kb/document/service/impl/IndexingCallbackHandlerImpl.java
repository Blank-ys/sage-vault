package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingCallbackRequest;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.IndexingTaskStatus;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.mapper.IndexingTaskMapper;
import com.sagevault.kb.document.service.IndexingCallbackHandler;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexingCallbackHandlerImpl implements IndexingCallbackHandler {
    private static final Logger log = LoggerFactory.getLogger(IndexingCallbackHandlerImpl.class);

    private final IndexingTaskMapper taskMapper;
    private final DocumentMapper documentMapper;

    public IndexingCallbackHandlerImpl(IndexingTaskMapper taskMapper, DocumentMapper documentMapper) {
        this.taskMapper = taskMapper;
        this.documentMapper = documentMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(IndexingCallbackRequest request) {
        IndexingTaskEntity task = taskMapper.findByTaskId(request.taskId());
        if (task == null) {
            throw new BusinessException(ErrorCode.INDEXING_TASK_NOT_FOUND, "入库任务不存在");
        }
        if (request.attempt() < task.getAttempt()) {
            log.info("Ignoring stale indexing callback for task {} attempt {} < {}",
                    request.taskId(), request.attempt(), task.getAttempt());
            return;
        }
        if (request.attempt() == task.getAttempt() && isTerminal(task.getStatus())) {
            log.info("Indexing callback for task {} attempt {} is already reconciled", request.taskId(), request.attempt());
            return;
        }
        IndexingTaskStatus taskStatus = request.success() ? IndexingTaskStatus.COMPLETED : IndexingTaskStatus.FAILED;
        DocumentStatus documentStatus = request.success() ? DocumentStatus.AVAILABLE : DocumentStatus.FAILED;
        String errorMessage = request.success() ? "" : buildFailureMessage(request.diagnostics());
        LocalDateTime callbackReceivedAt = LocalDateTime.now();
        int updated = taskMapper.updateTerminalState(request.taskId(), request.attempt(), taskStatus.name(),
                errorMessage, callbackReceivedAt);
        if (updated == 0) {
            log.info("Concurrent indexing callback for task {} attempt {} skipped", request.taskId(), request.attempt());
            return;
        }
        documentMapper.updateStatus(task.getDocumentId(), documentStatus.name(), errorMessage);
    }

    private static boolean isTerminal(IndexingTaskStatus status) {
        return status == IndexingTaskStatus.COMPLETED || status == IndexingTaskStatus.FAILED;
    }

    private static String buildFailureMessage(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "RAG 入库失败";
        }
        Object error = diagnostics.get("error");
        Object filename = diagnostics.get("filename");
        StringBuilder message = new StringBuilder("RAG 入库失败");
        if (filename != null && !filename.toString().isBlank()) {
            message.append("（").append(filename).append("）");
        }
        if (error != null && !error.toString().isBlank()) {
            message.append("：").append(error);
        }
        return message.toString();
    }
}
