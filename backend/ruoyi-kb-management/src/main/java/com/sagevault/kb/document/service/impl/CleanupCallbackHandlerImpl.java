package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.CleanupCallbackRequest;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.service.CleanupCallbackHandler;
import com.sagevault.kb.document.service.port.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CleanupCallbackHandlerImpl implements CleanupCallbackHandler {
    private static final Logger log = LoggerFactory.getLogger(CleanupCallbackHandlerImpl.class);

    private final DocumentMapper documentMapper;
    private final DocumentStorage storage;

    public CleanupCallbackHandlerImpl(DocumentMapper documentMapper, DocumentStorage storage) {
        this.documentMapper = documentMapper;
        this.storage = storage;
    }

    @Override
    public void handle(CleanupCallbackRequest request) {
        long documentId = Long.parseLong(request.documentId());
        DocumentEntity entity = documentMapper.findById(documentId);
        if (entity == null) {
            log.info("Cleanup callback for document {} ignored: record already removed", documentId);
            return;
        }
        if (entity.getStatus() != DocumentStatus.DELETING) {
            log.info("Cleanup callback for document {} ignored: status is {}", documentId, entity.getStatus());
            return;
        }
        if (!request.success()) {
            log.error("Cleanup failed for document {}: {}", documentId, request.diagnostics());
            return;
        }
        String prefix = extractPrefix(entity.getObjectKey());
        storage.deleteByPrefix(prefix);
        documentMapper.deleteById(documentId);
        log.info("Document {} cleaned up and record removed", documentId);
    }

    private static String extractPrefix(String objectKey) {
        int lastSlash = objectKey.lastIndexOf('/');
        if (lastSlash > 0) {
            return objectKey.substring(0, lastSlash + 1);
        }
        return objectKey;
    }
}
