package com.sagevault.kb.qarecord.service;

import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import java.util.List;

public interface QaRecordService {
    void create(long conversationId, long userId, String requestId, String generationId, String question);
    void appendAnswer(String generationId, String delta);
    void markCompleted(String generationId, String answer);
    void markRefused(String generationId, String answer);
    void markUnfinished(String generationId);

    /** 按提问时间正序返回会话内的问答历史。 */
    List<QaRecordResponse> listByConversation(long conversationId);

    /** 会话内是否已存在问答记录，用于判断首问。 */
    boolean hasRecords(long conversationId);

    /** 删除会话时清除该会话下的全部问答正文，返回被清除条数。 */
    int deleteByConversation(long conversationId);
}
