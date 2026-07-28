package com.sagevault.kb.document.service.port;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.IndexingTaskEntity;

public interface IndexingCommandDispatcher {
    void dispatch(DocumentEntity document, IndexingTaskEntity task);
}
