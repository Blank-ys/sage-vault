package com.sagevault.kb.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingCallbackRequest;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.IndexingTaskStatus;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.mapper.IndexingTaskMapper;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IndexingCallbackHandlerImplTest {
    @Test
    void marksDocumentAvailableWhenCallbackReportsSuccess() {
        IndexingTaskMapper taskMapper = mock(IndexingTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingCallbackHandlerImpl handler = new IndexingCallbackHandlerImpl(taskMapper, documentMapper);
        IndexingTaskEntity task = taskEntity(11L, "task-1", 1, IndexingTaskStatus.PROCESSING);
        when(taskMapper.findByTaskId("task-1")).thenReturn(task);
        when(taskMapper.updateTerminalState(eq("task-1"), eq(1), any(), any(), any())).thenReturn(1);

        handler.handle(new IndexingCallbackRequest("task-1", 1, "doc-1", true, 4, "req-1"));

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskMapper).updateTerminalState(eq("task-1"), eq(1), statusCaptor.capture(), errorCaptor.capture(),
                timeCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(IndexingTaskStatus.COMPLETED.name());
        verify(documentMapper).updateStatus(11L, DocumentStatus.AVAILABLE.name(), "");
    }

    @Test
    void marksDocumentFailedWhenCallbackReportsFailure() {
        IndexingTaskMapper taskMapper = mock(IndexingTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingCallbackHandlerImpl handler = new IndexingCallbackHandlerImpl(taskMapper, documentMapper);
        IndexingTaskEntity task = taskEntity(11L, "task-1", 1, IndexingTaskStatus.PROCESSING);
        when(taskMapper.findByTaskId("task-1")).thenReturn(task);
        when(taskMapper.updateTerminalState(eq("task-1"), eq(1), any(), any(), any())).thenReturn(1);

        handler.handle(new IndexingCallbackRequest("task-1", 1, "doc-1", false, 0, "req-1"));

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).updateTerminalState(eq("task-1"), eq(1), statusCaptor.capture(), any(), any());
        assertThat(statusCaptor.getValue()).isEqualTo(IndexingTaskStatus.FAILED.name());
        verify(documentMapper).updateStatus(11L, DocumentStatus.FAILED.name(), "RAG 入库失败");
    }

    @Test
    void ignoresStaleAttemptCallback() {
        IndexingTaskMapper taskMapper = mock(IndexingTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingCallbackHandlerImpl handler = new IndexingCallbackHandlerImpl(taskMapper, documentMapper);
        IndexingTaskEntity task = taskEntity(11L, "task-1", 2, IndexingTaskStatus.PROCESSING);
        when(taskMapper.findByTaskId("task-1")).thenReturn(task);

        handler.handle(new IndexingCallbackRequest("task-1", 1, "doc-1", true, 4, "req-1"));

        verify(taskMapper, never()).updateTerminalState(any(), anyInt(), any(), any(), any());
        verify(documentMapper, never()).updateStatus(anyLong(), any(), any());
    }

    @Test
    void idempotentlyAcceptsDuplicateCallbackForTerminalState() {
        IndexingTaskMapper taskMapper = mock(IndexingTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingCallbackHandlerImpl handler = new IndexingCallbackHandlerImpl(taskMapper, documentMapper);
        IndexingTaskEntity task = taskEntity(11L, "task-1", 1, IndexingTaskStatus.COMPLETED);
        when(taskMapper.findByTaskId("task-1")).thenReturn(task);

        handler.handle(new IndexingCallbackRequest("task-1", 1, "doc-1", true, 4, "req-1"));

        verify(taskMapper, never()).updateTerminalState(any(), anyInt(), any(), any(), any());
        verify(documentMapper, never()).updateStatus(anyLong(), any(), any());
    }

    @Test
    void throwsWhenTaskNotFound() {
        IndexingTaskMapper taskMapper = mock(IndexingTaskMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        IndexingCallbackHandlerImpl handler = new IndexingCallbackHandlerImpl(taskMapper, documentMapper);
        when(taskMapper.findByTaskId("task-1")).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(new IndexingCallbackRequest("task-1", 1, "doc-1", true, 4, "req-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                        .isEqualTo(ErrorCode.INDEXING_TASK_NOT_FOUND.code()));
    }

    private static IndexingTaskEntity taskEntity(long documentId, String taskId, int attempt,
            IndexingTaskStatus status) {
        IndexingTaskEntity task = new IndexingTaskEntity();
        task.setId(100L);
        task.setDocumentId(documentId);
        task.setTaskId(taskId);
        task.setAttempt(attempt);
        task.setStatus(status);
        return task;
    }
}
