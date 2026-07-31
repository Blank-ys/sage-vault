package com.sagevault.kb.document.service;

import com.sagevault.kb.document.domain.CleanupCallbackRequest;

public interface CleanupCallbackHandler {
    void handle(CleanupCallbackRequest request);
}
