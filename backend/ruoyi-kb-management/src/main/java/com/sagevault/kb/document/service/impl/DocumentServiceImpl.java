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
import com.sagevault.kb.document.service.port.IndexingCommandDispatcher;
import com.sagevault.kb.platform.error.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentServiceImpl implements DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentMapper mapper;
    private final DocumentRecordWriter recordWriter;
    private final IndexingTaskRecordWriter indexingTaskRecordWriter;
    private final MinioDocumentStorage storage;
    private final IndexingCommandDispatcher dispatcher;

    public DocumentServiceImpl(DocumentMapper mapper, DocumentRecordWriter recordWriter,
            IndexingTaskRecordWriter indexingTaskRecordWriter, MinioDocumentStorage storage,
            IndexingCommandDispatcher dispatcher) {
        this.mapper = mapper;
        this.recordWriter = recordWriter;
        this.indexingTaskRecordWriter = indexingTaskRecordWriter;
        this.storage = storage;
        this.dispatcher = dispatcher;
    }

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
