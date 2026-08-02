package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentFilename;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

        // 插入后复检：级联删除可能在"检查通过"与"插入完成"之间开始清理，
        // 那一轮扫描不到这条尚未提交的记录，放行就会在知识库删除后留下孤儿文档。
        // 复检失败时整个事务回滚，宁可让上传报错，也不让残留逃过清理窗口。
        knowledgeBases.requireAvailable(request.knowledgeBaseId());
        return entity;
    }

    @Override
    public void validateBatch(long knowledgeBaseId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "至少选择一个文件");
        }
        knowledgeBases.requireAvailable(knowledgeBaseId);

        Map<String, List<String>> normalizedToOriginals = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            DocumentFilename filename = DocumentFilename.of(file.getOriginalFilename());
            normalizedToOriginals.computeIfAbsent(filename.normalizedValue(), key -> new ArrayList<>())
                    .add(filename.value());
        }

        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : normalizedToOriginals.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.addAll(entry.getValue());
            }
        }
        List<DocumentEntity> existing = mapper.findByKbIdAndNormalizedNames(knowledgeBaseId,
                normalizedToOriginals.keySet());
        for (DocumentEntity entity : existing) {
            conflicts.addAll(normalizedToOriginals.get(entity.getNormalizedName()));
        }

        if (!conflicts.isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FILENAME_CONFLICT,
                    "以下文件名在知识库内或本批中已存在：" + String.join("、", conflicts));
        }
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
