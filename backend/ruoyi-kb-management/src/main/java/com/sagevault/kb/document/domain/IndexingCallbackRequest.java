package com.sagevault.kb.document.domain;

import java.util.Map;

public record IndexingCallbackRequest(String taskId, int attempt, String documentId, boolean success,
        int chunksCount, String requestId, Map<String, Object> diagnostics) {

    public IndexingCallbackRequest(String taskId, int attempt, String documentId, boolean success,
            int chunksCount, String requestId) {
        this(taskId, attempt, documentId, success, chunksCount, requestId, null);
    }
}
