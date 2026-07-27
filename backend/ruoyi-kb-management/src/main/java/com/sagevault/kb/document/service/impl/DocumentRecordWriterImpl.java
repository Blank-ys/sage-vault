package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentFilename;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DocumentRecordWriterImpl implements DocumentRecordWriter {
    private final DocumentMapper mapper;
    private final KnowledgeBaseService knowledgeBases;

    public DocumentRecordWriterImpl(DocumentMapper mapper, KnowledgeBaseService knowledgeBases) {
        this.mapper = mapper;
        this.knowledgeBases = knowledgeBases;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentEntity create(UploadDocumentRequest request) {
        knowledgeBases.requireAvailable(request.knowledgeBaseId());
        DocumentFilename filename = DocumentFilename.of(request.file().getOriginalFilename());
        ensureUnique(request.knowledgeBaseId(), filename.normalizedValue());

        String objectKey = objectKey(request.knowledgeBaseId(), filename.normalizedValue());
        DocumentEntity entity = entity(request.knowledgeBaseId(), filename, objectKey, request.file().getSize());
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw filenameConflict();
        }
        return entity;
    }

    private void ensureUnique(long knowledgeBaseId, String normalizedName) {
        DocumentEntity existing = mapper.findByKbIdAndNormalizedName(knowledgeBaseId, normalizedName);
        if (existing != null) {
            throw filenameConflict();
        }
    }

    private static DocumentEntity entity(long knowledgeBaseId, DocumentFilename filename, String objectKey, long size) {
        DocumentEntity entity = new DocumentEntity();
        entity.setKbId(knowledgeBaseId);
        entity.setFilename(filename.value());
        entity.setNormalizedName(filename.normalizedValue());
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setObjectKey(objectKey);
        entity.setSize(size);
        entity.setErrorMessage("");
        return entity;
    }

    private static String objectKey(long knowledgeBaseId, String normalizedName) {
        return "documents/" + knowledgeBaseId + "/" + UUID.randomUUID() + "/" + normalizedName;
    }

    private static BusinessException filenameConflict() {
        return new BusinessException(ErrorCode.DOCUMENT_FILENAME_CONFLICT, "该知识库下已存在同名文档");
    }
}
