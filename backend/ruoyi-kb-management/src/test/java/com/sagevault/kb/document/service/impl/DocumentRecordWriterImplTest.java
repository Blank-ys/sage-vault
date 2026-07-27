package com.sagevault.kb.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

class DocumentRecordWriterImplTest {
    @Test
    void createsProcessingRecordWithStableObjectKey() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, knowledgeBases);
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
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, knowledgeBases);
        doThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE, "知识库不可用"))
                .when(knowledgeBases).requireAvailable(7L);

        assertThatThrownBy(() -> writer.create(new UploadDocumentRequest(7L, file("x.txt", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库不可用");
    }

    @Test
    void rejectsDuplicateFilenameIgnoringCase() throws Exception {
        DocumentMapper mapper = mock(DocumentMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRecordWriterImpl writer = new DocumentRecordWriterImpl(mapper, knowledgeBases);
        DocumentEntity existing = new DocumentEntity();
        existing.setId(1L);
        existing.setFilename("Report.TXT");
        when(mapper.findByKbIdAndNormalizedName(anyLong(), anyString())).thenReturn(existing);

        assertThatThrownBy(() -> writer.create(new UploadDocumentRequest(7L, file("report.txt", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同名文档");
    }

    private static MultipartFile file(String name, byte[] content) throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        return file;
    }
}
