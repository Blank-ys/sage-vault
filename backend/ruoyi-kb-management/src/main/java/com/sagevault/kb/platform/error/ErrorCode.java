package com.sagevault.kb.platform.error;

public enum ErrorCode {
    KNOWLEDGE_BASE_NAME_CONFLICT(410001),
    KNOWLEDGE_BASE_NOT_AVAILABLE(410002),
    CONVERSATION_NOT_FOUND(410003),
    CONVERSATION_FORBIDDEN(410004),
    INVALID_REQUEST(410005),
    QA_RECORD_NOT_FOUND(410006),
    QA_RECORD_STATE_CONFLICT(410007),
    RAG_UNAVAILABLE(510001),
    AUDIT_UNAVAILABLE(510002);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
