package com.sagevault.kb.document.service;

import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import java.util.List;

public interface DocumentService {
    DocumentResponse upload(UploadDocumentRequest request);

    List<DocumentResponse> listByKnowledgeBase(long knowledgeBaseId);
}
