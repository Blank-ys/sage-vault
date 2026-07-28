package com.sagevault.kb.document.domain;

public enum IndexingTaskStatus {
    PROCESSING("处理中"),
    COMPLETED("已完成"),
    FAILED("失败");

    private final String desc;

    IndexingTaskStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
