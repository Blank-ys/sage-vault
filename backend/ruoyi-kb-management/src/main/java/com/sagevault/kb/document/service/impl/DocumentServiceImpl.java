package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentFilename;
import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.service.DocumentRecordWriter;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.document.service.port.CleanupCommandDispatcher;
import com.sagevault.kb.document.service.port.DocumentAudit;
import com.sagevault.kb.document.service.port.DocumentStorage;
import com.sagevault.kb.document.service.port.IndexingCommandDispatcher;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 企业文档生命周期的深实现：只负责编排（校验前置条件、派发外部命令、审计与响应组装）。
 * 全部状态迁移、幂等、尝试次数与 FAILSAFE 裁决委托给 {@link DocumentRecordWriter}；
 * MinIO、Python 命令与审计都经 port/adapter 进入。
 */
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentMapper mapper;
    private final DocumentRecordWriter recordWriter;
    private final DocumentStorage storage;
    private final IndexingCommandDispatcher dispatcher;
    private final CleanupCommandDispatcher cleanupDispatcher;
    private final DocumentAudit audit;

    @Override
    public DocumentResponse upload(UploadDocumentRequest request) {
        DocumentEntity entity = recordWriter.create(request);
        if (!storeOriginal(entity, request.file())) {
            audit.recordFailure(DocumentAudit.Operation.UPLOAD, entity.getId(), entity.getKbId(),
                    "读取上传文件失败");
            return response(entity);
        }
        IndexingTaskEntity task = recordWriter.createIndexingTask(entity);
        dispatcher.dispatch(entity, task);
        audit.record(DocumentAudit.Operation.UPLOAD, entity.getId(), entity.getKbId());
        return response(entity);
    }

    @Override
    public List<DocumentResponse> uploadBatch(long knowledgeBaseId, List<MultipartFile> files) {
        recordWriter.validateBatch(knowledgeBaseId, files);
        List<DocumentResponse> responses = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            responses.add(uploadOne(knowledgeBaseId, file));
        }
        return responses;
    }

    private DocumentResponse uploadOne(long knowledgeBaseId, MultipartFile file) {
        DocumentEntity entity = recordWriter.create(new UploadDocumentRequest(knowledgeBaseId, file));
        if (!storeOriginal(entity, file)) {
            audit.recordFailure(DocumentAudit.Operation.UPLOAD, entity.getId(), knowledgeBaseId,
                    "读取上传文件失败");
            return response(entity);
        }
        dispatchIndexing(entity);
        audit.record(DocumentAudit.Operation.UPLOAD, entity.getId(), knowledgeBaseId);
        return response(entity);
    }

    private void dispatchIndexing(DocumentEntity entity) {
        try {
            IndexingTaskEntity task = recordWriter.createIndexingTask(entity);
            dispatcher.dispatch(entity, task);
        } catch (RuntimeException exception) {
            log.error("Failed to create or dispatch indexing task for document {}", entity.getObjectKey(), exception);
            markFailed(entity, "索引任务派发失败：" + exception.getMessage());
        }
    }

    @Override
    public List<DocumentResponse> listByKnowledgeBase(long knowledgeBaseId) {
        return mapper.findByKbId(knowledgeBaseId).stream().map(this::response).toList();
    }

    @Override
    public boolean hasAvailableDocuments(long knowledgeBaseId) {
        int count = mapper.countAvailableByKbId(knowledgeBaseId);
        log.debug("Available documents count for knowledge base {}: {}", knowledgeBaseId, count);
        return count > 0;
    }

    @Override
    public DocumentResponse retry(long documentId) {
        IndexingTaskEntity task = recordWriter.beginRetry(documentId);
        DocumentEntity entity = mapper.findById(documentId);
        try {
            dispatcher.dispatch(entity, task);
            audit.record(DocumentAudit.Operation.RETRY, documentId, entity.getKbId());
        } catch (RuntimeException exception) {
            log.error("Failed to dispatch retry indexing task for document {}", documentId, exception);
            String message = "重试派发失败：" + exception.getMessage();
            recordWriter.failTask(task, message);
            markFailed(entity, message);
            audit.recordFailure(DocumentAudit.Operation.RETRY, documentId, entity.getKbId(), message);
        }
        return response(entity);
    }

    @Override
    public DocumentResponse delete(long documentId) {
        DocumentEntity entity = mapper.findById(documentId);
        if (entity == null) {
            audit.recordFailure(DocumentAudit.Operation.DELETE, documentId, 0L, "文档不存在");
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在");
        }

        // 幂等性：已在删除中的文档直接返回当前状态
        if (entity.getStatus() == DocumentStatus.DELETING) {
            log.info("Document {} is already in DELETING status, delete is idempotent", documentId);
            return response(entity);
        }

        // 幂等性：清理失败的文档不允许直接删除，必须通过重试清理接口
        if (entity.getStatus() == DocumentStatus.CLEANUP_FAILED) {
            audit.recordFailure(DocumentAudit.Operation.DELETE, documentId, entity.getKbId(),
                    "文档清理失败，请重试清理操作");
            throw new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT,
                    "文档清理失败，请重试清理操作");
        }

        // 仅 AVAILABLE 状态可以进入删除流程
        if (!recordWriter.beginDelete(documentId)) {
            audit.recordFailure(DocumentAudit.Operation.DELETE, documentId, entity.getKbId(),
                    "文档当前状态不允许删除");
            throw new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT, "文档当前状态不允许删除");
        }
        entity.setStatus(DocumentStatus.DELETING);
        try {
            cleanupDispatcher.dispatch(entity);
        } catch (RuntimeException exception) {
            log.error("Failed to dispatch cleanup for document {}", documentId, exception);
            recordWriter.restoreAfterDeleteDispatchFailure(documentId);
            audit.recordFailure(DocumentAudit.Operation.DELETE, documentId, entity.getKbId(),
                    "清理命令派发失败，请稍后重试");
            throw new BusinessException(ErrorCode.CLEANUP_DISPATCH_FAILED, "清理命令派发失败，请稍后重试");
        }
        audit.record(DocumentAudit.Operation.DELETE, documentId, entity.getKbId());
        return response(entity);
    }

    @Override
    public DocumentResponse cleanupRetry(long documentId) {
        IndexingTaskEntity task = recordWriter.beginCleanupRetry(documentId);
        DocumentEntity entity = mapper.findById(documentId);
        try {
            cleanupDispatcher.dispatch(entity);
            audit.record(DocumentAudit.Operation.CLEANUP_RETRY, documentId, entity.getKbId());
        } catch (RuntimeException exception) {
            log.error("Failed to dispatch retry cleanup for document {}", documentId, exception);
            String message = "清理重试派发失败：" + exception.getMessage();
            recordWriter.failTask(task, message);
            // 回退到 CLEANUP_FAILED
            recordWriter.failCleanupRetry(documentId, message);
            audit.recordFailure(DocumentAudit.Operation.CLEANUP_RETRY, documentId, entity.getKbId(), message);
            throw new BusinessException(ErrorCode.CLEANUP_DISPATCH_FAILED, message);
        }
        return response(entity);
    }

    private boolean storeOriginal(DocumentEntity entity, MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            String contentType = DocumentFilename.of(entity.getFilename()).contentType();
            storage.save(entity.getObjectKey(), content, entity.getSize(), contentType);
            return true;
        } catch (IOException exception) {
            log.error("Failed to read uploaded document {}", entity.getObjectKey(), exception);
            markFailed(entity, "读取上传文件失败");
            return false;
        } catch (BusinessException exception) {
            markFailed(entity, exception.getMessage());
            return false;
        }
    }

    private void markFailed(DocumentEntity entity, String message) {
        recordWriter.failDocument(entity.getId(), message);
        entity.setStatus(DocumentStatus.FAILED);
        entity.setErrorMessage(message);
    }

    private DocumentResponse response(DocumentEntity entity) {
        return new DocumentResponse(entity.getId(), entity.getKbId(), entity.getFilename(),
                entity.getNormalizedName(), entity.getStatus(), entity.getSize(),
                entity.getErrorMessage(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
