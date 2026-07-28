package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.adapter.MinioDocumentStorage;
import com.sagevault.kb.document.domain.DocumentEntity;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentServiceImpl implements DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    private static final String CONTENT_TYPE_TXT = "text/plain";

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
        if (!storeOriginal(entity, request)) {
            return response(entity);
        }
        IndexingTaskEntity task = indexingTaskRecordWriter.create(entity);
        dispatcher.dispatch(entity, task);
        return response(entity);
    }

    @Override
    public List<DocumentResponse> listByKnowledgeBase(long knowledgeBaseId) {
        return mapper.findByKbId(knowledgeBaseId).stream().map(this::response).toList();
    }

    private boolean storeOriginal(DocumentEntity entity, UploadDocumentRequest request) {
        try (InputStream content = request.file().getInputStream()) {
            storage.save(entity.getObjectKey(), content, entity.getSize(), CONTENT_TYPE_TXT);
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
