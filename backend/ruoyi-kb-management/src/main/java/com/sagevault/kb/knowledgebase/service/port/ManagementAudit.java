package com.sagevault.kb.knowledgebase.service.port;

public interface ManagementAudit {
    void record(Operation operation, long knowledgeBaseId);
    enum Operation { CREATE, UPDATE, DELETE }
}
