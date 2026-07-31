package com.sagevault.kb.document.domain;

public enum DocumentStatus {
    PROCESSING("处理中"),
    AVAILABLE("可用"),
    FAILED("失败"),
    DELETING("删除中");

    private final String desc;

    DocumentStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
