package com.sagevault.kb.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.IndexingTaskStatus;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.mapper.IndexingTaskMapper;
import com.sagevault.kb.document.service.AutoCleanupTask;
import com.sagevault.kb.document.service.DocumentRecordWriter;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

class DocumentRecordWriterImplTest {

    @Test
    void createsProcessingRecordWithStableObjectKey() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        MultipartFile file = file("Report.TXT", "content".getBytes());

        DocumentEntity entity = writer.create(new UploadDocumentRequest(7L, file));

        assertThat(entity.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(entity.getNormalizedName()).isEqualTo("report.txt");
        assertThat(entity.getObjectKey()).startsWith("documents/7/");
        assertThat(entity.getObjectKey()).endsWith("/report.txt");
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo(7L);
    }

    @Test
    void rejectsUploadWhenKnowledgeBaseIsUnavailable() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        doThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE, "知识库不可用"))
                .when(knowledgeBases).requireAvailable(7L);

        assertThatThrownBy(() -> writer.create(new UploadDocumentRequest(7L, file("x.txt", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库不可用");
    }

    @Test
    void rejectsDuplicateFilenameIgnoringCase() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        DocumentEntity existing = new DocumentEntity();
        existing.setId(1L);
        existing.setFilename("Report.TXT");
        when(mapper.findByKbIdAndNormalizedName(anyLong(), anyString())).thenReturn(existing);

        assertThatThrownBy(() -> writer.create(new UploadDocumentRequest(7L, file("report.txt", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同名文档");
    }

    @Test
    void validateBatchPassesWhenAllFilesAreUnique() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        when(mapper.findByKbIdAndNormalizedNames(eq(7L), any(Collection.class))).thenReturn(List.of());

        writer.validateBatch(7L, List.of(file("alpha.txt", new byte[0]), file("beta.pdf", new byte[0])));

        verify(mapper).findByKbIdAndNormalizedNames(eq(7L), any(Collection.class));
    }

    @Test
    void validateBatchRejectsEmptyFileList() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);

        assertThatThrownBy(() -> writer.validateBatch(7L, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少选择一个文件");
    }

    @Test
    void validateBatchRejectsWhenKnowledgeBaseUnavailable() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        doThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE, "知识库不可用"))
                .when(knowledgeBases).requireAvailable(7L);

        assertThatThrownBy(() -> writer.validateBatch(7L, List.of(file("x.txt", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库不可用");
    }

    @Test
    void validateBatchRejectsWithinBatchCaseInsensitiveDuplicates() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        when(mapper.findByKbIdAndNormalizedNames(eq(7L), any(Collection.class))).thenReturn(List.of());

        assertThatThrownBy(() -> writer.validateBatch(7L,
                List.of(file("Report.TXT", new byte[0]), file("report.txt", new byte[0]), file("ok.pdf", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Report.TXT")
                .hasMessageContaining("report.txt");
    }

    @Test
    void validateBatchRejectsConflictsWithExistingRecords() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        DocumentEntity existing = new DocumentEntity();
        existing.setId(1L);
        existing.setNormalizedName("alpha.txt");
        when(mapper.findByKbIdAndNormalizedNames(eq(7L), any(Collection.class))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> writer.validateBatch(7L,
                List.of(file("Alpha.TXT", new byte[0]), file("beta.pdf", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Alpha.TXT");
    }

    @Test
    void validateBatchReportsAllConflictsInOneMessage() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper, knowledgeBases);
        DocumentEntity existing = new DocumentEntity();
        existing.setNormalizedName("old.pdf");
        when(mapper.findByKbIdAndNormalizedNames(eq(7L), any(Collection.class))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> writer.validateBatch(7L,
                List.of(file("old.pdf", new byte[0]),
                        file("dup.txt", new byte[0]),
                        file("DUP.TXT", new byte[0]),
                        file("clean.md", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("old.pdf")
                .hasMessageContaining("dup.txt")
                .hasMessageContaining("DUP.TXT");
    }

    @Test
    void createIndexingTaskCreatesAttemptOneTask() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity document = documentEntity(11L, DocumentStatus.PROCESSING);

        IndexingTaskEntity task = writer.createIndexingTask(document);

        assertThat(task.getDocumentId()).isEqualTo(11L);
        assertThat(task.getAttempt()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(IndexingTaskStatus.PROCESSING);
        verify(indexingTaskMapper).insert(task);
    }

    @Test
    void beginRetryFailsWhenDocumentNotFound() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));

        assertThatThrownBy(() -> writer.beginRetry(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND.code()));
    }

    @Test
    void beginRetryFailsWhenDocumentNotFailed() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity entity = documentEntity(11L, DocumentStatus.AVAILABLE);
        when(mapper.findById(11L)).thenReturn(entity);
        when(mapper.updateStatusIfCurrentStatus(11L, DocumentStatus.PROCESSING.name(), "",
                DocumentStatus.FAILED.name())).thenReturn(0);

        assertThatThrownBy(() -> writer.beginRetry(11L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DOCUMENT_STATE_CONFLICT.code()))
                .hasMessageContaining("仅处理失败的文档可以重试");
    }

    @Test
    void beginRetryTransitionsToProcessingAndIncrementsAttempt() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity entity = documentEntity(11L, DocumentStatus.FAILED);
        when(mapper.findById(11L)).thenReturn(entity);
        when(mapper.updateStatusIfCurrentStatus(11L, DocumentStatus.PROCESSING.name(), "",
                DocumentStatus.FAILED.name())).thenReturn(1);
        IndexingTaskEntity previous = new IndexingTaskEntity();
        previous.setAttempt(1);
        when(indexingTaskMapper.findLatestByDocumentId(11L)).thenReturn(previous);

        IndexingTaskEntity task = writer.beginRetry(11L);

        assertThat(task.getDocumentId()).isEqualTo(11L);
        assertThat(task.getAttempt()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(IndexingTaskStatus.PROCESSING);
        verify(indexingTaskMapper).insert(task);
    }

    @Test
    void beginCleanupRetryFailsWhenDocumentNotFound() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));

        assertThatThrownBy(() -> writer.beginCleanupRetry(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND.code()));

        verify(mapper).findById(999L);
    }

    @Test
    void beginCleanupRetryFailsWhenDocumentNotInRetryableState() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity entity = documentEntity(11L, DocumentStatus.AVAILABLE);
        when(mapper.findById(11L)).thenReturn(entity);

        assertThatThrownBy(() -> writer.beginCleanupRetry(11L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DOCUMENT_STATE_CONFLICT.code()))
                .hasMessageContaining("仅处于 DELETING 或 CLEANUP_FAILED 的文档可以重试清理");

        verify(mapper).findById(11L);
    }

    @Test
    void beginCleanupRetryAllowsRetryFromDeletingState() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity entity = documentEntity(11L, DocumentStatus.DELETING);
        entity.setCleanupPhase("MILVUS_CLEANUP");
        entity.setCleanupAttempt(3);
        when(mapper.findById(11L)).thenReturn(entity);
        IndexingTaskEntity previousTask = new IndexingTaskEntity();
        previousTask.setAttempt(3);
        when(indexingTaskMapper.findLatestCleanupByDocumentId(11L)).thenReturn(previousTask);
        when(mapper.incrementCleanupAttemptWhileDeleting(11L, 4)).thenReturn(1);

        IndexingTaskEntity task = writer.beginCleanupRetry(11L);

        assertThat(task.getDocumentId()).isEqualTo(11L);
        assertThat(task.getTaskType()).isEqualTo("CLEANUP");
        assertThat(task.getAttempt()).isEqualTo(4);
        assertThat(task.getStatus()).isEqualTo(IndexingTaskStatus.PROCESSING);
        verify(mapper).incrementCleanupAttemptWhileDeleting(11L, 4);
        verify(indexingTaskMapper).insert(task);
    }

    @Test
    void beginCleanupRetrySuccessfullyTransitionsFromCleanupFailed() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity entity = documentEntity(11L, DocumentStatus.CLEANUP_FAILED);
        entity.setCleanupPhase("MILVUS_CLEANUP");
        when(mapper.findById(11L)).thenReturn(entity);
        when(indexingTaskMapper.findLatestCleanupByDocumentId(11L)).thenReturn(null);
        when(mapper.incrementCleanupAttempt(11L, 1, "MILVUS_CLEANUP")).thenReturn(1);

        IndexingTaskEntity task = writer.beginCleanupRetry(11L);

        assertThat(task.getDocumentId()).isEqualTo(11L);
        assertThat(task.getTaskType()).isEqualTo("CLEANUP");
        assertThat(task.getAttempt()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(IndexingTaskStatus.PROCESSING);
        verify(mapper).incrementCleanupAttempt(11L, 1, "MILVUS_CLEANUP");
        verify(indexingTaskMapper).insert(task);
    }

    @Test
    void beginCleanupRetryIncrementsAttemptWhenPreviousCleanupExists() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity entity = documentEntity(11L, DocumentStatus.CLEANUP_FAILED);
        entity.setCleanupPhase("MINIO_CLEANUP");
        when(mapper.findById(11L)).thenReturn(entity);
        IndexingTaskEntity previousTask = new IndexingTaskEntity();
        previousTask.setAttempt(2);
        when(indexingTaskMapper.findLatestCleanupByDocumentId(11L)).thenReturn(previousTask);
        when(mapper.incrementCleanupAttempt(11L, 3, "MINIO_CLEANUP")).thenReturn(1);

        IndexingTaskEntity task = writer.beginCleanupRetry(11L);

        assertThat(task.getAttempt()).isEqualTo(3);
        verify(mapper).incrementCleanupAttempt(11L, 3, "MINIO_CLEANUP");
        verify(indexingTaskMapper).insert(task);
    }

    @Test
    void beginCleanupRetryFailsWhenCasUpdateFails() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity entity = documentEntity(11L, DocumentStatus.CLEANUP_FAILED);
        entity.setCleanupPhase("MILVUS_CLEANUP");
        when(mapper.findById(11L)).thenReturn(entity);
        when(mapper.incrementCleanupAttempt(11L, 1, "MILVUS_CLEANUP")).thenReturn(0);

        assertThatThrownBy(() -> writer.beginCleanupRetry(11L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DOCUMENT_STATE_CONFLICT.code()))
                .hasMessageContaining("文档状态已变更，清理重试无法继续");

        verify(mapper).incrementCleanupAttempt(11L, 1, "MILVUS_CLEANUP");
        verify(indexingTaskMapper, never()).insert(any());
    }

    @Test
    void failTaskUpdatesTerminalState() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        IndexingTaskEntity task = taskEntity("cleanup-task-1", 1);

        writer.failTask(task, "Connection timeout");

        verify(indexingTaskMapper).updateTerminalState(
                eq("cleanup-task-1"), eq(1),
                eq(IndexingTaskStatus.FAILED.name()),
                eq("Connection timeout"),
                any());
    }

    @Test
    void beginDeleteReturnsTrueWhenCasSucceeds() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        when(mapper.updateStatusIfCurrentStatus(11L, DocumentStatus.DELETING.name(), "",
                DocumentStatus.AVAILABLE.name())).thenReturn(1);

        assertThat(writer.beginDelete(11L)).isTrue();
    }

    @Test
    void beginDeleteReturnsFalseWhenStateNoLongerAvailable() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        when(mapper.updateStatusIfCurrentStatus(11L, DocumentStatus.DELETING.name(), "",
                DocumentStatus.AVAILABLE.name())).thenReturn(0);

        assertThat(writer.beginDelete(11L)).isFalse();
    }

    @Test
    void failStuckCleaningMarksStuckDeletingDocuments() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity stuck = documentEntity(11L, DocumentStatus.DELETING);
        stuck.setCleanupPhase("MILVUS_CLEANUP");
        when(mapper.findStuckCleaningDocuments(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD))
                .thenReturn(List.of(stuck));
        when(mapper.markCleanupFailed(eq(11L), anyString())).thenReturn(1);

        int marked = writer.failStuckCleaning(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD);

        assertThat(marked).isEqualTo(1);
        verify(mapper).findStuckCleaningDocuments(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD);
        verify(mapper).markCleanupFailed(eq(11L), anyString());
    }

    @Test
    void failStuckCleaningSkipsWhenNothingStuck() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        when(mapper.findStuckCleaningDocuments(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD))
                .thenReturn(List.of());

        int marked = writer.failStuckCleaning(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD);

        assertThat(marked).isZero();
        verify(mapper, never()).markCleanupFailed(anyLong(), anyString());
    }

    @Test
    void failStuckCleaningDoesNotCountWhenCasMisses() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        IndexingTaskMapper indexingTaskMapper = mock(IndexingTaskMapper.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, indexingTaskMapper,
                mock(KnowledgeBaseService.class));
        DocumentEntity stuck = documentEntity(11L, DocumentStatus.DELETING);
        stuck.setCleanupPhase("MINIO_CLEANUP");
        when(mapper.findStuckCleaningDocuments(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD))
                .thenReturn(List.of(stuck));
        when(mapper.markCleanupFailed(eq(11L), anyString())).thenReturn(0);

        int marked = writer.failStuckCleaning(AutoCleanupTask.FAILSAFE_ATTEMPT_THRESHOLD);

        assertThat(marked).isZero();
        verify(mapper).markCleanupFailed(eq(11L), anyString());
    }

    private static DocumentEntity documentEntity(long id, DocumentStatus status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setStatus(status);
        return entity;
    }

    private static IndexingTaskEntity taskEntity(String taskId, int attempt) {
        IndexingTaskEntity task = new IndexingTaskEntity();
        task.setId(100L);
        task.setTaskId(taskId);
        task.setAttempt(attempt);
        task.setStatus(IndexingTaskStatus.PROCESSING);
        return task;
    }

    private static MultipartFile file(String name, byte[] content) throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        return file;
    }
}
