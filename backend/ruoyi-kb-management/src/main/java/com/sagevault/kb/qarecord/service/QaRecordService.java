package com.sagevault.kb.qarecord.service;

import com.sagevault.kb.feedback.domain.RetrievedChunkDiagnostic;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

public interface QaRecordService {
    void create(long conversationId, long userId, String requestId, String generationId, String question);
    void appendAnswer(String generationId, String delta);
    void markCompleted(String generationId, String answer);
    void markRefused(String generationId, String answer);

    /**
     * 落库一次回答的全链路诊断：检索召回的片段标识/分数与阶段毫秒耗时。
     * 不携带片段正文，遵守隐私约束。generationId 不存在时静默跳过，不抛出业务异常。
     */
    void saveDiagnostics(String generationId, List<RetrievedChunkDiagnostic> retrievalDiagnostics,
            Map<String, Integer> stageDurations);

    /**
     * 生成中途 RAG 管线失败：保留已落库的残缺正文，裁决为生成失败。
     * detail 为脱敏后的受控失败类别，调用方不得传入原始异常文本或知识库 id。
     * @return 本次是否首次裁决为终态；若此前已是终态则返回 false（幂等）
     */
    boolean markFailed(String generationId, String detail);

    /** 连接断开等非业务原因中断：保留已落库的残缺正文，仅裁决为未完成。 */
    void markUnfinished(String generationId);

    /**
     * 用户显式停止：保留已落库的残缺正文，裁决为已停止。
     *
     * @return true 表示本次调用赢得了从 STARTED 到 STOPPED 的迁移；false 表示该回答已处于其他终态
     */
    boolean markStopped(String generationId);

    /** 按提问时间正序返回会话内的问答历史。 */
    List<QaRecordResponse> listByConversation(long conversationId);

    /** 会话内是否已存在问答记录，用于判断首问。 */
    boolean hasRecords(long conversationId);

    /** 会话内是否已有进行中（STARTED）的回答，用于并发串行化。 */
    boolean hasPending(long conversationId);

    /** 按 generationId 返回问答记录，不存在时返回 null。 */
    @Nullable
    QaRecordEntity findByGenerationId(String generationId);

    /** 删除会话时清除该会话下的全部问答正文，返回被清除条数。 */
    int deleteByConversation(long conversationId);
}
