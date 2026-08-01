package com.sagevault.kb.conversation.service.port;

/**
 * 会话删除审计。只允许透出会话标识与被清除条数，不得携带问题、答案或标题等正文。
 */
public interface ConversationAudit {
    void recordDeleted(long conversationId, int removedRecordCount);
}
