package com.sagevault.kb.knowledgebase.domain;

public enum KnowledgeBaseStatus {
    AVAILABLE("Available"), DELETING("Deleting"), DELETE_FAILED("Delete failed");

    private final String desc;

    KnowledgeBaseStatus(String desc) { this.desc = desc; }
    public String getDesc() { return desc; }
}
