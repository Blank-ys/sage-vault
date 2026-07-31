package com.sagevault.kb.document.domain;

import java.util.Map;

public record CleanupCallbackRequest(String taskId, String documentId, boolean success,
        String requestId, Map<String, Object> diagnostics) {

    public CleanupCallbackRequest(String taskId, String documentId, boolean success, String requestId) {
        this(taskId, documentId, success, requestId, null);
    }
}
