package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.IndexingTaskStatus;
import com.sagevault.kb.document.mapper.IndexingTaskMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IndexingTaskRecordWriter {
    private final IndexingTaskMapper mapper;

    public IndexingTaskRecordWriter(IndexingTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public IndexingTaskEntity create(DocumentEntity document) {
        IndexingTaskEntity task = new IndexingTaskEntity();
        task.setDocumentId(document.getId());
        task.setTaskId(UUID.randomUUID().toString());
        task.setAttempt(1);
        task.setStatus(IndexingTaskStatus.PROCESSING);
        task.setErrorMessage("");
        mapper.insert(task);
        return task;
    }
}
