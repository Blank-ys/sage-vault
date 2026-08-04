package com.sagevault.kb.knowledgebase.service.port;

public interface ManagementAudit {
    void record(Operation operation, long knowledgeBaseId);

    /**
     * 记录一次失败的管理操作，写入 RuoYi 操作审计入口。
     *
     * @param operation       操作类型
     * @param knowledgeBaseId 知识库标识，操作未创建对象时为 0
     * @param errorMessage     失败原因，仅含标识与状态，不得包含业务正文
     */
    void recordFailure(Operation operation, long knowledgeBaseId, String errorMessage);

    enum Operation { CREATE, UPDATE, DELETE }
}
