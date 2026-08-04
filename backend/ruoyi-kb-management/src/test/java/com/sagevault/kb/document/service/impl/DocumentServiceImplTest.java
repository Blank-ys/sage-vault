package com.sagevault.kb.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sagevault.kb.document.adapter.MinioDocumentStorage;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.IndexingTaskStatus;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.service.port.CleanupCommandDispatcher;
import com.sagevault.kb.document.service.port.IndexingCommandDispatcher;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

class DocumentServiceImplTest {
    @Test
    void createsProcessingRecordThenStoresOriginalDispatchesTaskAndReturnsIt() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        IndexingTaskRecordWriter indexingTaskRecordWriter = mock(IndexingTaskRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, indexingTaskRecordWriter,
                mock(RetryRecordWriter.class), mock(CleanupRecordWriter.class), storage, dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        byte[] content = "hello world".getBytes();
        MultipartFile file = file("notes.txt", content);
        DocumentEntity entity = processingEntity(7L, 11L, "notes.txt", "documents/11/uuid/notes.txt", content.length);
        IndexingTaskEntity task = taskEntity(entity.getId(), "task-1");
        when(recordWriter.create(any())).thenReturn(entity);
        when(indexingTaskRecordWriter.create(entity)).thenReturn(task);

        DocumentResponse response = service.upload(new UploadDocumentRequest(7L, file));

        assertThat(response.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(response.filename()).isEqualTo("notes.txt");
        assertThat(response.size()).isEqualTo(11L);
        verify(storage).save(eq(entity.getObjectKey()), any(InputStream.class), eq(11L), eq("text/plain"));
        verify(indexingTaskRecordWriter).create(entity);
        verify(dispatcher).dispatch(entity, task);
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    void storesOriginalWithContentTypeMatchingFileExtension() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        IndexingTaskRecordWriter indexingTaskRecordWriter = mock(IndexingTaskRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, indexingTaskRecordWriter,
                mock(RetryRecordWriter.class), mock(CleanupRecordWriter.class), storage, dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));

        uploadAndVerifyContentType(recordWriter, indexingTaskRecordWriter, storage, service,
                "spec.pdf", "application/pdf");
        uploadAndVerifyContentType(recordWriter, indexingTaskRecordWriter, storage, service,
                "guide.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        uploadAndVerifyContentType(recordWriter, indexingTaskRecordWriter, storage, service,
                "readme.md", "text/markdown");
    }

    private static void uploadAndVerifyContentType(DocumentRecordWriter recordWriter,
            IndexingTaskRecordWriter indexingTaskRecordWriter, MinioDocumentStorage storage,
            DocumentServiceImpl service, String filename, String expectedContentType) throws Exception {
        byte[] content = "content".getBytes();
        MultipartFile file = file(filename, content);
        DocumentEntity entity = processingEntity(7L, 11L, filename, "documents/11/uuid/" + filename, content.length);
        IndexingTaskEntity task = taskEntity(entity.getId(), "task-1");
        when(recordWriter.create(any())).thenReturn(entity);
        when(indexingTaskRecordWriter.create(entity)).thenReturn(task);

        service.upload(new UploadDocumentRequest(7L, file));

        verify(storage).save(eq(entity.getObjectKey()), any(InputStream.class), eq((long) content.length),
                eq(expectedContentType));
        clearInvocations(storage);
    }

    @Test
    void marksFailedWhenStorageThrowsBusinessException() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        IndexingTaskRecordWriter indexingTaskRecordWriter = mock(IndexingTaskRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, indexingTaskRecordWriter,
                mock(RetryRecordWriter.class), mock(CleanupRecordWriter.class), storage, dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        byte[] content = "content".getBytes();
        MultipartFile file = file("notes.txt", content);
        DocumentEntity entity = processingEntity(7L, 11L, "notes.txt", "documents/11/uuid/notes.txt", content.length);
        when(recordWriter.create(any())).thenReturn(entity);
        doThrow(new BusinessException(ErrorCode.DOCUMENT_STORAGE_FAILED, "存储服务不可用"))
                .when(storage).save(anyString(), any(InputStream.class), anyLong(), anyString());

        DocumentResponse response = service.upload(new UploadDocumentRequest(7L, file));

        assertThat(response.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(response.errorMessage()).isEqualTo("存储服务不可用");
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateStatus(eq(entity.getId()), statusCaptor.capture(), eq("存储服务不可用"));
        assertThat(statusCaptor.getValue()).isEqualTo(DocumentStatus.FAILED.name());
        verify(indexingTaskRecordWriter, never()).create(any());
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void listsDocumentsByKnowledgeBase() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentEntity first = processingEntity(7L, 1L, "a.txt", "key-a", 5);
        DocumentEntity second = processingEntity(7L, 2L, "b.txt", "key-b", 5);
        when(mapper.findByKbId(7L)).thenReturn(List.of(first, second));

        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), mock(RetryRecordWriter.class),
                mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class),                 mock(IndexingCommandDispatcher.class),
                mock(CleanupCommandDispatcher.class),
                mock(com.sagevault.kb.document.service.port.DocumentAudit.class));

        assertThat(service.listByKnowledgeBase(7L)).extracting(DocumentResponse::filename)
                .containsExactly("a.txt", "b.txt");
    }

    @Test
    void uploadBatchProcessesEachFileIndependentlyAfterValidation() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        IndexingTaskRecordWriter indexingTaskRecordWriter = mock(IndexingTaskRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, indexingTaskRecordWriter,
                mock(RetryRecordWriter.class), mock(CleanupRecordWriter.class), storage, dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        MultipartFile firstFile = file("alpha.txt", "alpha".getBytes());
        MultipartFile secondFile = file("beta.pdf", "beta".getBytes());
        DocumentEntity firstEntity = processingEntity(7L, 11L, "alpha.txt", "key-a", 5);
        DocumentEntity secondEntity = processingEntity(7L, 12L, "beta.pdf", "key-b", 4);
        IndexingTaskEntity firstTask = taskEntity(11L, "task-1");
        IndexingTaskEntity secondTask = taskEntity(12L, "task-2");
        when(recordWriter.create(new UploadDocumentRequest(7L, firstFile))).thenReturn(firstEntity);
        when(recordWriter.create(new UploadDocumentRequest(7L, secondFile))).thenReturn(secondEntity);
        when(indexingTaskRecordWriter.create(firstEntity)).thenReturn(firstTask);
        when(indexingTaskRecordWriter.create(secondEntity)).thenReturn(secondTask);

        List<DocumentResponse> responses = service.uploadBatch(7L, List.of(firstFile, secondFile));

        verify(recordWriter).validateBatch(7L, List.of(firstFile, secondFile));
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).filename()).isEqualTo("alpha.txt");
        assertThat(responses.get(1).filename()).isEqualTo("beta.pdf");
        verify(dispatcher).dispatch(firstEntity, firstTask);
        verify(dispatcher).dispatch(secondEntity, secondTask);
    }

    @Test
    void uploadBatchRejectsEntireBatchWhenValidationFailsWithoutPersistence() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        IndexingTaskRecordWriter indexingTaskRecordWriter = mock(IndexingTaskRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, indexingTaskRecordWriter,
                mock(RetryRecordWriter.class), mock(CleanupRecordWriter.class), storage, dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        MultipartFile firstFile = file("alpha.txt", "alpha".getBytes());
        MultipartFile secondFile = file("alpha.txt", "alpha".getBytes());
        doThrow(new BusinessException(ErrorCode.DOCUMENT_FILENAME_CONFLICT, "以下文件名在知识库内或本批中已存在：alpha.txt"))
                .when(recordWriter).validateBatch(eq(7L), any());

        assertThatThrownBy(() -> service.uploadBatch(7L, List.of(firstFile, secondFile)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("alpha.txt");

        verify(recordWriter, never()).create(any());
        verifyNoInteractions(storage, indexingTaskRecordWriter, dispatcher);
    }

    @Test
    void uploadBatchContinuesOtherFilesWhenOneStorageFails() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        IndexingTaskRecordWriter indexingTaskRecordWriter = mock(IndexingTaskRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, indexingTaskRecordWriter,
                mock(RetryRecordWriter.class), mock(CleanupRecordWriter.class), storage, dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        MultipartFile firstFile = file("alpha.txt", "alpha".getBytes());
        MultipartFile secondFile = file("beta.pdf", "beta".getBytes());
        DocumentEntity firstEntity = processingEntity(7L, 11L, "alpha.txt", "key-a", 5);
        DocumentEntity secondEntity = processingEntity(7L, 12L, "beta.pdf", "key-b", 4);
        IndexingTaskEntity secondTask = taskEntity(12L, "task-2");
        when(recordWriter.create(any())).thenReturn(firstEntity, secondEntity);
        doThrow(new BusinessException(ErrorCode.DOCUMENT_STORAGE_FAILED, "存储服务不可用"))
                .when(storage).save(eq("key-a"), any(InputStream.class), anyLong(), anyString());
        when(indexingTaskRecordWriter.create(secondEntity)).thenReturn(secondTask);

        List<DocumentResponse> responses = service.uploadBatch(7L, List.of(firstFile, secondFile));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(responses.get(1).status()).isEqualTo(DocumentStatus.PROCESSING);
        verify(dispatcher, never()).dispatch(eq(firstEntity), any());
        verify(dispatcher).dispatch(secondEntity, secondTask);
    }

    @Test
    void uploadBatchContinuesOtherFilesWhenOneDispatchFails() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        IndexingTaskRecordWriter indexingTaskRecordWriter = mock(IndexingTaskRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, indexingTaskRecordWriter,
                mock(RetryRecordWriter.class), mock(CleanupRecordWriter.class), storage, dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        MultipartFile firstFile = file("alpha.txt", "alpha".getBytes());
        MultipartFile secondFile = file("beta.pdf", "beta".getBytes());
        DocumentEntity firstEntity = processingEntity(7L, 11L, "alpha.txt", "key-a", 5);
        DocumentEntity secondEntity = processingEntity(7L, 12L, "beta.pdf", "key-b", 4);
        IndexingTaskEntity firstTask = taskEntity(11L, "task-1");
        IndexingTaskEntity secondTask = taskEntity(12L, "task-2");
        when(recordWriter.create(any())).thenReturn(firstEntity, secondEntity);
        when(indexingTaskRecordWriter.create(firstEntity)).thenReturn(firstTask);
        when(indexingTaskRecordWriter.create(secondEntity)).thenReturn(secondTask);
        doThrow(new BusinessException(ErrorCode.RAG_UNAVAILABLE, "RAG 服务暂不可用"))
                .when(dispatcher).dispatch(eq(firstEntity), eq(firstTask));

        List<DocumentResponse> responses = service.uploadBatch(7L, List.of(firstFile, secondFile));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(responses.get(0).errorMessage()).contains("RAG 服务暂不可用");
        assertThat(responses.get(1).status()).isEqualTo(DocumentStatus.PROCESSING);
        verify(mapper).updateStatus(eq(firstEntity.getId()), eq(DocumentStatus.FAILED.name()), anyString());
        verify(dispatcher).dispatch(secondEntity, secondTask);
    }

    private static DocumentEntity processingEntity(long kbId, long id, String filename, String objectKey, long size) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setKbId(kbId);
        entity.setFilename(filename);
        entity.setNormalizedName(filename.toLowerCase());
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setObjectKey(objectKey);
        entity.setSize(size);
        entity.setErrorMessage("");
        return entity;
    }

    private static IndexingTaskEntity taskEntity(long documentId, String taskId) {
        IndexingTaskEntity task = new IndexingTaskEntity();
        task.setId(100L);
        task.setDocumentId(documentId);
        task.setTaskId(taskId);
        task.setAttempt(1);
        task.setStatus(IndexingTaskStatus.PROCESSING);
        return task;
    }

    private static MultipartFile file(String name, byte[] content) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        return file;
    }

    @Test
    void retryDispatchesNewTaskAndReturnsProcessingDocument() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        RetryRecordWriter retryRecordWriter = mock(RetryRecordWriter.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), retryRecordWriter, mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class),
                dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = processingEntity(7L, 11L, "notes.txt", "documents/11/uuid/notes.txt", 5);
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setErrorMessage("");
        IndexingTaskEntity retryTask = taskEntity(11L, "task-retry-2");
        retryTask.setAttempt(2);
        when(retryRecordWriter.beginRetry(11L)).thenReturn(retryTask);
        when(mapper.findById(11L)).thenReturn(entity);

        DocumentResponse response = service.retry(11L);

        assertThat(response.status()).isEqualTo(DocumentStatus.PROCESSING);
        verify(retryRecordWriter).beginRetry(11L);
        verify(dispatcher).dispatch(entity, retryTask);
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    void retryMarksFailedWhenDispatchThrows() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        RetryRecordWriter retryRecordWriter = mock(RetryRecordWriter.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), retryRecordWriter, mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class),
                dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = processingEntity(7L, 11L, "notes.txt", "documents/11/uuid/notes.txt", 5);
        entity.setStatus(DocumentStatus.PROCESSING);
        IndexingTaskEntity retryTask = taskEntity(11L, "task-retry-2");
        retryTask.setAttempt(2);
        when(retryRecordWriter.beginRetry(11L)).thenReturn(retryTask);
        when(mapper.findById(11L)).thenReturn(entity);
        doThrow(new BusinessException(ErrorCode.RAG_UNAVAILABLE, "RAG 服务暂不可用"))
                .when(dispatcher).dispatch(eq(entity), eq(retryTask));

        DocumentResponse response = service.retry(11L);

        assertThat(response.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(response.errorMessage()).contains("RAG 服务暂不可用");
        verify(mapper).updateStatus(eq(11L), eq(DocumentStatus.FAILED.name()), anyString());
        verify(retryRecordWriter).failTask(eq(retryTask), anyString());
    }

    @Test
    void retryPropagatesStateConflictFromRecordWriter() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        RetryRecordWriter retryRecordWriter = mock(RetryRecordWriter.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), retryRecordWriter, mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class),
                dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        when(retryRecordWriter.beginRetry(11L)).thenThrow(
                new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT, "仅处理失败的文档可以重试"));

        assertThatThrownBy(() -> service.retry(11L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                        .isEqualTo(ErrorCode.DOCUMENT_STATE_CONFLICT.code()));

        verify(dispatcher, never()).dispatch(any(), any());
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    void retryPreservesDocumentIdentityAndFilename() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        RetryRecordWriter retryRecordWriter = mock(RetryRecordWriter.class);
        IndexingCommandDispatcher dispatcher = mock(IndexingCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), retryRecordWriter, mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class),
                dispatcher, mock(CleanupCommandDispatcher.class), mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = processingEntity(7L, 11L, "report.txt", "documents/11/uuid/report.txt", 10);
        entity.setStatus(DocumentStatus.PROCESSING);
        IndexingTaskEntity retryTask = taskEntity(11L, "task-retry-2");
        retryTask.setAttempt(2);
        when(retryRecordWriter.beginRetry(11L)).thenReturn(retryTask);
        when(mapper.findById(11L)).thenReturn(entity);

        DocumentResponse response = service.retry(11L);

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.filename()).isEqualTo("report.txt");
        assertThat(response.knowledgeBaseId()).isEqualTo(7L);
        verify(mapper, never()).insert(any());
    }

    @Test
    void deleteIsIdempotentWhenDocumentIsAlreadyDeleting() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        CleanupCommandDispatcher cleanupDispatcher = mock(CleanupCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), mock(RetryRecordWriter.class),
                mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class), mock(IndexingCommandDispatcher.class), cleanupDispatcher, mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setKbId(7L);
        entity.setFilename("report.txt");
        entity.setSize(100L);
        entity.setStatus(DocumentStatus.DELETING);
        when(mapper.findById(11L)).thenReturn(entity);

        service.delete(11L);

        verify(mapper, never()).updateStatusIfCurrentStatus(anyLong(), anyString(), anyString(), anyString());
        verify(cleanupDispatcher, never()).dispatch(any());
    }

    @Test
    void deleteRejectsCleanupFailedDocument() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), mock(RetryRecordWriter.class),
                mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class),                 mock(IndexingCommandDispatcher.class),
                mock(CleanupCommandDispatcher.class),
                mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.CLEANUP_FAILED);
        when(mapper.findById(11L)).thenReturn(entity);

        assertThatThrownBy(() -> service.delete(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("清理失败");

        verify(mapper, never()).updateStatusIfCurrentStatus(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void deleteReturnsDocumentResponseFollowingDocumentResponseShape() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        CleanupCommandDispatcher cleanupDispatcher = mock(CleanupCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), mock(RetryRecordWriter.class),
                mock(CleanupRecordWriter.class), mock(MinioDocumentStorage.class), mock(IndexingCommandDispatcher.class), cleanupDispatcher, mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setKbId(7L);
        entity.setFilename("report.txt");
        entity.setNormalizedName("report.txt");
        entity.setStatus(DocumentStatus.AVAILABLE);
        entity.setSize(100L);
        when(mapper.findById(11L)).thenReturn(entity);
        when(mapper.updateStatusIfCurrentStatus(eq(11L), eq(DocumentStatus.DELETING.name()),
                eq(""), eq(DocumentStatus.AVAILABLE.name()))).thenReturn(1);

        DocumentResponse response = service.delete(11L);

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.status()).isEqualTo(DocumentStatus.DELETING);
        assertThat(response.filename()).isEqualTo("report.txt");
        verify(cleanupDispatcher).dispatch(entity);
    }

    @Test
    void cleanupRetryDispatchesCleanupAndReturnsResponse() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        CleanupRecordWriter cleanupRecordWriter = mock(CleanupRecordWriter.class);
        CleanupCommandDispatcher cleanupDispatcher = mock(CleanupCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), mock(RetryRecordWriter.class), cleanupRecordWriter,
                mock(MinioDocumentStorage.class), mock(IndexingCommandDispatcher.class), cleanupDispatcher, mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.DELETING);
        entity.setFilename("report.txt");
        entity.setKbId(7L);
        entity.setSize(100L);
        IndexingTaskEntity cleanupTask = taskEntity(11L, "cleanup-task-1");
        cleanupTask.setTaskType("CLEANUP");
        when(cleanupRecordWriter.beginCleanupRetry(11L)).thenReturn(cleanupTask);
        when(mapper.findById(11L)).thenReturn(entity);

        DocumentResponse response = service.cleanupRetry(11L);

        assertThat(response.status()).isEqualTo(DocumentStatus.DELETING);
        assertThat(response.filename()).isEqualTo("report.txt");
        verify(cleanupRecordWriter).beginCleanupRetry(11L);
        verify(cleanupDispatcher).dispatch(entity);
    }

    @Test
    void cleanupRetryMarksFailedWhenDispatchThrows() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        CleanupRecordWriter cleanupRecordWriter = mock(CleanupRecordWriter.class);
        CleanupCommandDispatcher cleanupDispatcher = mock(CleanupCommandDispatcher.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(IndexingTaskRecordWriter.class), mock(RetryRecordWriter.class), cleanupRecordWriter,
                mock(MinioDocumentStorage.class), mock(IndexingCommandDispatcher.class), cleanupDispatcher, mock(com.sagevault.kb.document.service.port.DocumentAudit.class));
        DocumentEntity entity = new DocumentEntity();
        entity.setId(11L);
        entity.setStatus(DocumentStatus.DELETING);
        IndexingTaskEntity cleanupTask = taskEntity(11L, "cleanup-task-1");
        cleanupTask.setTaskType("CLEANUP");
        when(cleanupRecordWriter.beginCleanupRetry(11L)).thenReturn(cleanupTask);
        when(mapper.findById(11L)).thenReturn(entity);
        doThrow(new BusinessException(ErrorCode.RAG_UNAVAILABLE, "RAG 服务暂不可用"))
                .when(cleanupDispatcher).dispatch(entity);

        assertThatThrownBy(() -> service.cleanupRetry(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RAG 服务暂不可用");

        verify(cleanupRecordWriter).failTask(eq(cleanupTask), anyString());
        verify(mapper).updateStatusIfCurrentStatus(eq(11L), eq(DocumentStatus.CLEANUP_FAILED.name()),
                anyString(), eq(DocumentStatus.DELETING.name()));
    }
}
