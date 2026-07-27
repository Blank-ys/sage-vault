package com.sagevault.kb.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.document.adapter.MinioDocumentStorage;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

class DocumentServiceImplTest {
    @Test
    void createsProcessingRecordThenStoresOriginalAndReturnsIt() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, storage);
        byte[] content = "hello world".getBytes();
        MultipartFile file = file("notes.txt", content);
        DocumentEntity entity = processingEntity(7L, 11L, "notes.txt", "documents/11/uuid/notes.txt", content.length);
        when(recordWriter.create(any())).thenReturn(entity);

        DocumentResponse response = service.upload(new UploadDocumentRequest(7L, file));

        assertThat(response.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(response.filename()).isEqualTo("notes.txt");
        assertThat(response.size()).isEqualTo(11L);
        verify(storage).save(eq(entity.getObjectKey()), any(InputStream.class), eq(11L), eq("text/plain"));
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    void marksFailedWhenStorageThrowsBusinessException() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentRecordWriter recordWriter = mock(DocumentRecordWriter.class);
        MinioDocumentStorage storage = mock(MinioDocumentStorage.class);
        DocumentServiceImpl service = new DocumentServiceImpl(mapper, recordWriter, storage);
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
    }

    @Test
    void listsDocumentsByKnowledgeBase() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentEntity first = processingEntity(7L, 1L, "a.txt", "key-a", 5);
        DocumentEntity second = processingEntity(7L, 2L, "b.txt", "key-b", 5);
        when(mapper.findByKbId(7L)).thenReturn(List.of(first, second));

        DocumentServiceImpl service = new DocumentServiceImpl(mapper, mock(DocumentRecordWriter.class),
                mock(MinioDocumentStorage.class));

        assertThat(service.listByKnowledgeBase(7L)).extracting(DocumentResponse::filename)
                .containsExactly("a.txt", "b.txt");
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

    private static MultipartFile file(String name, byte[] content) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        return file;
    }
}
