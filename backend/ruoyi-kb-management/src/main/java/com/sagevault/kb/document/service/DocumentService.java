package com.sagevault.kb.document.service;

import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
    DocumentResponse upload(UploadDocumentRequest request);

    List<DocumentResponse> uploadBatch(long knowledgeBaseId, List<MultipartFile> files);

    List<DocumentResponse> listByKnowledgeBase(long knowledgeBaseId);

    DocumentResponse retry(long documentId);
}
