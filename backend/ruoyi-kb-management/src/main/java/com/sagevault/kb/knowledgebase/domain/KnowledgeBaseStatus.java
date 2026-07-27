package com.sagevault.kb.knowledgebase.domain;

public enum KnowledgeBaseStatus {
    AVAILABLE("可用"), DELETING("删除中"), DELETE_FAILED("删除失败");

    private final String desc;

    KnowledgeBaseStatus(String desc) { this.desc = desc; }
    public String getDesc() { return desc; }
}
