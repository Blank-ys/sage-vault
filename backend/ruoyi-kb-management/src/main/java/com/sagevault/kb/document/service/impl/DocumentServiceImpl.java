package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.adapter.MinioDocumentStorage;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentFilename;
import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.document.service.port.CleanupCommandDispatcher;
import com.sagevault.kb.document.service.port.IndexingCommandDispatcher;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentMapper mapper;
    private final DocumentRecordWriter recordWriter;
    private final IndexingTaskRecordWriter indexingTaskRecordWriter;
    private final RetryRecordWriter retryRecordWriter;
    private final CleanupRecordWriter cleanupRecordWriter;
    private final MinioDocumentStorage storage;
    private final IndexingCommandDispatcher dispatcher;
    private final CleanupCommandDispatcher cleanupDispatcher;

    @Override
    public DocumentResponse upload(UploadDocumentRequest request) {
        DocumentEntity entity = recordWriter.create(request);
        if (!storeOriginal(entity, request.file())) {
            return response(entity);
        }
        IndexingTaskEntity task = indexingTaskRecordWriter.create(entity);
        dispatcher.dispatch(entity, task);
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
            return response(entity);
        }
        dispatchIndexing(entity);
        return response(entity);
    }

    private void dispatchIndexing(DocumentEntity entity) {
        try {
            IndexingTaskEntity task = indexingTaskRecordWriter.create(entity);
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
        IndexingTaskEntity task = retryRecordWriter.beginRetry(documentId);
        DocumentEntity entity = mapper.findById(documentId);
        try {
            dispatcher.dispatch(entity, task);
        } catch (RuntimeException exception) {
            log.error("Failed to dispatch retry indexing task for document {}", documentId, exception);
            String message = "重试派发失败：" + exception.getMessage();
            retryRecordWriter.failTask(task, message);
            markFailed(entity, message);
        }
        return response(entity);
    }

    @Override
    public DocumentResponse delete(long documentId) {
        DocumentEntity entity = mapper.findById(documentId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在");
        }

        // 幂等性：已在删除中的文档直接返回当前状态
        if (entity.getStatus() == DocumentStatus.DELETING) {
            log.info("Document {} is already in DELETING status, delete is idempotent", documentId);
            return response(entity);
        }

        // 幂等性：清理失败的文档不允许直接删除，必须通过重试清理接口
        if (entity.getStatus() == DocumentStatus.CLEANUP_FAILED) {
            throw new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT,
                    "文档清理失败，请重试清理操作");
        }

        // 仅 AVAILABLE 状态可以进入删除流程
        int updated = mapper.updateStatusIfCurrentStatus(documentId,
                DocumentStatus.DELETING.name(), "", DocumentStatus.AVAILABLE.name());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_STATE_CONFLICT, "文档当前状态不允许删除");
        }
        entity.setStatus(DocumentStatus.DELETING);
        try {
            cleanupDispatcher.dispatch(entity);
        } catch (RuntimeException exception) {
            log.error("Failed to dispatch cleanup for document {}", documentId, exception);
            mapper.updateStatusIfCurrentStatus(documentId,
                    DocumentStatus.AVAILABLE.name(), "", DocumentStatus.DELETING.name());
            throw new BusinessException(ErrorCode.CLEANUP_DISPATCH_FAILED, "清理命令派发失败，请稍后重试");
        }
        return response(entity);
    }

    @Override
    public DocumentResponse cleanupRetry(long documentId) {
        IndexingTaskEntity task = cleanupRecordWriter.beginCleanupRetry(documentId);
        DocumentEntity entity = mapper.findById(documentId);
        try {
            cleanupDispatcher.dispatch(entity);
        } catch (RuntimeException exception) {
            log.error("Failed to dispatch retry cleanup for document {}", documentId, exception);
            String message = "清理重试派发失败：" + exception.getMessage();
            cleanupRecordWriter.failTask(task, message);
            // 回退到 CLEANUP_FAILED
            mapper.updateStatusIfCurrentStatus(documentId,
                    DocumentStatus.CLEANUP_FAILED.name(), message, DocumentStatus.DELETING.name());
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
        mapper.updateStatus(entity.getId(), DocumentStatus.FAILED.name(), message);
        entity.setStatus(DocumentStatus.FAILED);
        entity.setErrorMessage(message);
    }

    private DocumentResponse response(DocumentEntity entity) {
        return new DocumentResponse(entity.getId(), entity.getKbId(), entity.getFilename(),
                entity.getNormalizedName(), entity.getStatus(), entity.getSize(),
                entity.getErrorMessage(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
