package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentRecordWriter {
    DocumentEntity create(UploadDocumentRequest request);

    void validateBatch(long knowledgeBaseId, List<MultipartFile> files);
}
