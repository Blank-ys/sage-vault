package com.sagevault.kb.document.service;

import com.sagevault.kb.document.domain.IndexingCallbackRequest;

public interface IndexingCallbackHandler {
    void handle(IndexingCallbackRequest request);
}
