package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.CleanupCallbackRequest;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.mapper.IndexingTaskMapper;
import com.sagevault.kb.document.service.CleanupCallbackHandler;
import com.sagevault.kb.document.service.port.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CleanupCallbackHandlerImpl implements CleanupCallbackHandler {
    private static final Logger log = LoggerFactory.getLogger(CleanupCallbackHandlerImpl.class);

    private final DocumentMapper documentMapper;
    private final IndexingTaskMapper indexingTaskMapper;
    private final DocumentStorage storage;

    public CleanupCallbackHandlerImpl(DocumentMapper documentMapper,
            IndexingTaskMapper indexingTaskMapper, DocumentStorage storage) {
        this.documentMapper = documentMapper;
        this.indexingTaskMapper = indexingTaskMapper;
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

        // 终态检查：已完成清理的记录（已被删除或不在 DELETING 状态）
        if (entity.getStatus() != DocumentStatus.DELETING) {
            log.info("Cleanup callback for document {} ignored: status is {}", documentId, entity.getStatus());
            return;
        }

        if (!request.success()) {
            // 清理失败：转换为 CLEANUP_FAILED 并记录诊断信息
            String phase = request.phase() != null ? request.phase() : "UNKNOWN";
            String diagnostics = buildDiagnostics(request);
            log.error("Cleanup failed for document {}, phase: {}, diagnostics: {}", documentId, phase, diagnostics);
            documentMapper.updateStatus(documentId, DocumentStatus.CLEANUP_FAILED.name(),
                    "清理失败 [" + phase + "]：" + diagnostics);
            return;
        }

        // 清理成功：删除 MinIO 原文件、索引任务与文档记录
        String prefix = extractPrefix(entity.getObjectKey());
        storage.deleteByPrefix(prefix);
        indexingTaskMapper.deleteByDocumentId(documentId);
        documentMapper.deleteById(documentId);
        log.info("Document {} cleaned up and record removed", documentId);
    }

    private String buildDiagnostics(CleanupCallbackRequest request) {
        Map<String, Object> diagnostics = request.diagnostics();
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "无详细诊断信息";
        }
        return diagnostics.toString();
    }

    private static String extractPrefix(String objectKey) {
        int lastSlash = objectKey.lastIndexOf('/');
        if (lastSlash > 0) {
            return objectKey.substring(0, lastSlash + 1);
        }
        return objectKey;
    }
}
