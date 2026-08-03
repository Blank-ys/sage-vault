package com.sagevault.kb.document.service.port;

public interface DocumentAudit {

    enum Operation { UPLOAD, DELETE, RETRY, CLEANUP_RETRY }

    /** 记录一次成功的企业文档管理操作，写入 RuoYi 操作审计入口。 */
    void record(Operation operation, long documentId, Long knowledgeBaseId);

    /**
     * 记录一次失败的企业文档管理操作，写入 RuoYi 操作审计入口。
     *
     * @param operation        操作类型
     * @param documentId       文档标识，操作未创建对象时为 0
     * @param knowledgeBaseId  知识库标识，可能为 null
     * @param errorMessage     失败原因，仅含标识与状态，不得包含文档正文
     */
    void recordFailure(Operation operation, long documentId, Long knowledgeBaseId, String errorMessage);
}
