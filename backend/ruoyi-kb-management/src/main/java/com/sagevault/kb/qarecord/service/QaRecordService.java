package com.sagevault.kb.qarecord.service;

import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import java.util.List;
import org.springframework.lang.Nullable;

public interface QaRecordService {
    void create(long conversationId, long userId, String requestId, String generationId, String question);
    void appendAnswer(String generationId, String delta);
    void markCompleted(String generationId, String answer);
    void markRefused(String generationId, String answer);

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
