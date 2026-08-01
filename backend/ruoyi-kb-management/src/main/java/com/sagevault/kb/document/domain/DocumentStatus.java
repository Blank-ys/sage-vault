package com.sagevault.kb.document.domain;

public enum DocumentStatus {
    PROCESSING("处理中"),
    AVAILABLE("可用"),
    FAILED("失败"),
    DELETING("删除中"),
    CLEANUP_FAILED("清理失败");

    private final String desc;

    DocumentStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
