package com.sagevault.kb.qarecord.domain;

public enum QaRecordStatus {
    STARTED("已开始"),
    REFUSED("已拒答"),
    COMPLETED("已完成"),
    STOPPED("已停止"),
    UNFINISHED("未完成");

    private final String desc;

    QaRecordStatus(String desc) {
        this.desc = desc;
    }

    public String desc() {
        return desc;
    }
}
