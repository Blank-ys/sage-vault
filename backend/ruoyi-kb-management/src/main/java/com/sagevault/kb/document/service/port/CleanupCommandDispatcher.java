package com.sagevault.kb.document.service.port;

import com.sagevault.kb.document.domain.DocumentEntity;

public interface CleanupCommandDispatcher {
    void dispatch(DocumentEntity document);
}
