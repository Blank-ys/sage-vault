package com.sagevault.kb.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.document.domain.CleanupCallbackRequest;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.mapper.IndexingTaskMapper;
import com.sagevault.kb.document.service.AutoCleanupTask;
import com.sagevault.kb.document.service.DocumentRecordWriter;
import com.sagevault.kb.document.service.port.DocumentAudit;
import com.sagevault.kb.document.service.port.DocumentStorage;
import com.sagevault.kb.document.service.port.IndexingCommandDispatcher;
import com.sagevault.kb.document.service.port.CleanupCommandDispatcher;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CleanupFailureTest {

    @Test
    void cleanupCallbackHandlerIgnoresMissingDocument() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentStorage storage = mock(DocumentStorage.class);

        CleanupCallbackHandlerImpl handler = new CleanupCallbackHandlerImpl(
                documentMapper, indexingTaskMapper, storage);

        handler.handle(new CleanupCallbackRequest("task-999", "999", true, "req-1"));

        verify(documentMapper).findById(999L);
        verify(storage, never()).deleteByPrefix(anyString());
        verify(indexingTaskMapper, never()).deleteByDocumentId(anyLong());
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    void cleanupCallbackHandlerIgnoresFinalStateDocument() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentStorage storage = mock(DocumentStorage.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.AVAILABLE); // Final state - not DELETING
        when(documentMapper.findById(11L)).thenReturn(entity);

        CleanupCallbackHandlerImpl handler = new CleanupCallbackHandlerImpl(
                documentMapper, indexingTaskMapper, storage);

        handler.handle(new CleanupCallbackRequest("task-11", "11", true, "req-1"));

        verify(storage, never()).deleteByPrefix(anyString());
        verify(indexingTaskMapper, never()).deleteByDocumentId(anyLong());
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    void cleanupCallbackHandlerHandlesSuccessAndDeletesRecord() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentStorage storage = mock(DocumentStorage.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.DELETING);
        entity.setObjectKey("documents/11/uuid/test.txt");
        when(documentMapper.findById(11L)).thenReturn(entity);

        CleanupCallbackHandlerImpl handler = new CleanupCallbackHandlerImpl(
                documentMapper, indexingTaskMapper, storage);

        handler.handle(new CleanupCallbackRequest("task-11", "11", true, "req-1"));

        verify(storage).deleteByPrefix(eq("documents/11/uuid/"));
        verify(indexingTaskMapper).deleteByDocumentId(11L);
        verify(documentMapper).deleteById(11L);
    }

    @Test
    void cleanupCallbackHandlerHandlesFailureAndTransitionsToCleanupFailed() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentStorage storage = mock(DocumentStorage.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.DELETING);
        entity.setObjectKey("documents/11/uuid/test.txt");
        when(documentMapper.findById(11L)).thenReturn(entity);

        CleanupCallbackHandlerImpl handler = new CleanupCallbackHandlerImpl(
                documentMapper, indexingTaskMapper, storage);

        handler.handle(new CleanupCallbackRequest("task-11", "11", false, "req-1", "VECTOR_DELETE",
                Map.of("error", "Connection refused")));

        verify(documentMapper).updateStatus(eq(11L), eq(DocumentStatus.CLEANUP_FAILED.name()),
                eq("清理失败 [VECTOR_DELETE]：{error=Connection refused}"));
        verify(storage, never()).deleteByPrefix(anyString());
        verify(indexingTaskMapper, never()).deleteByDocumentId(anyLong());
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    void cleanupCallbackHandlerHandlesFailureWithUnknownPhaseWhenPhaseIsNull() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentStorage storage = mock(DocumentStorage.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.DELETING);
        entity.setObjectKey("documents/11/uuid/test.txt");
        when(documentMapper.findById(11L)).thenReturn(entity);

        CleanupCallbackHandlerImpl handler = new CleanupCallbackHandlerImpl(
                documentMapper, indexingTaskMapper, storage);

        handler.handle(new CleanupCallbackRequest("task-11", "11", false, "req-1", null,
                Map.of("error", "Connection refused")));

        verify(documentMapper).updateStatus(eq(11L), eq(DocumentStatus.CLEANUP_FAILED.name()),
                eq("清理失败 [UNKNOWN]：{error=Connection refused}"));
        verify(storage, never()).deleteByPrefix(anyString());
        verify(indexingTaskMapper, never()).deleteByDocumentId(anyLong());
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    void cleanupCallbackHandlerHandlesFailureWithNoDiagnostics() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentStorage storage = mock(DocumentStorage.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.DELETING);
        entity.setObjectKey("documents/11/uuid/test.txt");
        when(documentMapper.findById(11L)).thenReturn(entity);

        CleanupCallbackHandlerImpl handler = new CleanupCallbackHandlerImpl(
                documentMapper, indexingTaskMapper, storage);

        handler.handle(new CleanupCallbackRequest("task-11", "11", false, "req-1", "MINIO_DELETE", null));

        verify(documentMapper).updateStatus(eq(11L), eq(DocumentStatus.CLEANUP_FAILED.name()),
                eq("清理失败 [MINIO_DELETE]：无详细诊断信息"));
        verify(storage, never()).deleteByPrefix(anyString());
        verify(indexingTaskMapper, never()).deleteByDocumentId(anyLong());
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    void autoCleanupTaskDelegatesFailSafeAdjudicationToRecordWriter() {
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        when(recordWriter.failStuckCleaning(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD)).thenReturn(1);

        AutoCleanupTask task = new AutoCleanupTask(recordWriter);
        task.detectStuckCleaning();

        verify(recordWriter).failStuckCleaning(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD);
    }

    @Test
    void documentServiceDeleteIsIdempotentWhenAlreadyDeleting() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setKbId(7L);
        entity.setFilename("report.txt");
        entity.setSize(100L);
        entity.setStatus(DocumentStatus.DELETING);
        when(documentMapper.findById(11L)).thenReturn(entity);

        DocumentServiceImpl service = new DocumentServiceImpl(documentMapper, recordWriter,
                mock(DocumentStorage.class), mock(IndexingCommandDispatcher.class),
                mock(CleanupCommandDispatcher.class), mock(DocumentAudit.class));

        service.delete(11L);

        verify(recordWriter, never()).beginDelete(anyLong());
    }

    @Test
    void documentServiceDeleteRejectsCleanupFailedDocuments() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.CLEANUP_FAILED);
        when(documentMapper.findById(11L)).thenReturn(entity);

        DocumentServiceImpl service = new DocumentServiceImpl(documentMapper, recordWriter,
                mock(DocumentStorage.class), mock(IndexingCommandDispatcher.class),
                mock(CleanupCommandDispatcher.class), mock(DocumentAudit.class));

        assertThatThrownBy(() -> service.delete(11L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DOCUMENT_STATE_CONFLICT.code()))
                .hasMessageContaining("文档清理失败，请重试清理操作");
    }

    @Test
    void documentServiceCleanupRetryDispatchesSuccessfully() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        CleanupCommandDispatcher cleanupDispatcher = mock(CleanupCommandDispatcher.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setSize(1024L);
        entity.setKbId(7L);
        entity.setStatus(DocumentStatus.CLEANUP_FAILED);
        entity.setFilename("test.txt");
        when(documentMapper.findById(11L)).thenReturn(entity);
        IndexingTaskEntity cleanupTask = new IndexingTaskEntity();
        cleanupTask.setId(100L);
        cleanupTask.setTaskId("cleanup-retry-1");
        cleanupTask.setDocumentId(11L);
        cleanupTask.setAttempt(1);
        cleanupTask.setTaskType("CLEANUP");
        when(recordWriter.beginCleanupRetry(11L)).thenReturn(cleanupTask);

        DocumentServiceImpl service = new DocumentServiceImpl(documentMapper, recordWriter,
                mock(DocumentStorage.class), mock(IndexingCommandDispatcher.class),
                cleanupDispatcher, mock(DocumentAudit.class));

        service.cleanupRetry(11L);

        verify(recordWriter).beginCleanupRetry(11L);
        verify(cleanupDispatcher).dispatch(entity);
    }

    @Test
    void documentServiceCleanupRetryHandlesDispatchFailure() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        CleanupCommandDispatcher cleanupDispatcher = mock(CleanupCommandDispatcher.class);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setKbId(7L);
        entity.setStatus(DocumentStatus.CLEANUP_FAILED);
        when(documentMapper.findById(11L)).thenReturn(entity);
        IndexingTaskEntity cleanupTask = new IndexingTaskEntity();
        cleanupTask.setId(100L);
        cleanupTask.setTaskId("cleanup-retry-1");
        cleanupTask.setDocumentId(11L);
        cleanupTask.setAttempt(1);
        cleanupTask.setTaskType("CLEANUP");
        when(recordWriter.beginCleanupRetry(11L)).thenReturn(cleanupTask);

        // Simulate dispatch failure
        doThrow(new BusinessException(ErrorCode.RAG_UNAVAILABLE, "RAG 服务暂不可用"))
                .when(cleanupDispatcher).dispatch(entity);

        DocumentServiceImpl service = new DocumentServiceImpl(documentMapper, recordWriter,
                mock(DocumentStorage.class), mock(IndexingCommandDispatcher.class),
                cleanupDispatcher, mock(DocumentAudit.class));

        assertThatThrownBy(() -> service.cleanupRetry(11L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.CLEANUP_DISPATCH_FAILED.code()));

        verify(recordWriter).beginCleanupRetry(11L);
        verify(recordWriter).failTask(any(), anyString());
        verify(recordWriter).failCleanupRetry(eq(11L), anyString());
    }
}
