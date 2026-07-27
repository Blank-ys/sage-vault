package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.UploadDocumentRequest;

public interface DocumentRecordWriter {
    DocumentEntity create(UploadDocumentRequest request);
}
