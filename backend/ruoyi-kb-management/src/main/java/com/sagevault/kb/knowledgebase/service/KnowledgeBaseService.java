package com.sagevault.kb.knowledgebase.service;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseResponse;
import com.sagevault.kb.knowledgebase.domain.UpdateKnowledgeBaseRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface KnowledgeBaseService {
    KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request);
    KnowledgeBaseResponse get(long id);
    KnowledgeBaseResponse update(long id, UpdateKnowledgeBaseRequest request);
    List<KnowledgeBaseResponse> listAll();
    List<KnowledgeBaseResponse> listAvailable();
    void requireAvailable(long knowledgeBaseId);

    /**
     * 发起知识库级联删除：知识库立即进入 DELETING，随后由后台清理推进文档、原文件与向量的移除。
     *
     * <p>进入 DELETING 后 {@link #requireAvailable(long)} 即拒绝新的上传、会话与提问，
     * 因此该方法返回即代表"不再接受新写入"这一承诺已生效。已在 DELETING 的知识库重复调用为幂等。
     */
    KnowledgeBaseResponse delete(long id);

    /**
     * 批量解析知识库名称，供历史会话等只读视图区分"知识库仍在"与"知识库已删除"。
     *
     * <p>只返回仍存在活动记录的知识库；调用方在结果中找不到的 ID 即为已删除，
     * 因此该方法不会因知识库被删除而抛错。
     */
    Map<Long, String> resolveNames(Set<Long> knowledgeBaseIds);
}
