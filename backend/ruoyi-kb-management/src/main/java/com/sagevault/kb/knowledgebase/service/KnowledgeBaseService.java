package com.sagevault.kb.knowledgebase.service;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseResponse;
import com.sagevault.kb.knowledgebase.domain.UpdateKnowledgeBaseRequest;
import java.util.List;

public interface KnowledgeBaseService {
    KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request);
    KnowledgeBaseResponse get(long id);
    KnowledgeBaseResponse update(long id, UpdateKnowledgeBaseRequest request);
    List<KnowledgeBaseResponse> listAll();
    List<KnowledgeBaseResponse> listAvailable();
    void requireAvailable(long knowledgeBaseId);
}
