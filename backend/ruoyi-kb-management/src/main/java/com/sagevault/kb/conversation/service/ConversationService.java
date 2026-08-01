package com.sagevault.kb.conversation.service;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.domain.RenameConversationRequest;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import java.util.List;
import reactor.core.publisher.Flux;

public interface ConversationService {
    ConversationResponse create(long userId, CreateConversationRequest request);

    Flux<AnswerEvent> ask(long userId, long conversationId, AskQuestionRequest request);

    /** 按最近活跃时间倒序返回当前用户自己的会话，不包含他人会话。 */
    List<ConversationResponse> list(long userId);

    /** 读取归属于当前用户的单个会话。 */
    ConversationResponse get(long userId, long conversationId);

    /** 读取归属于当前用户的会话内问答历史，按提问时间正序。 */
    List<QaRecordResponse> history(long userId, long conversationId);

    /** 重命名归属于当前用户的会话。 */
    ConversationResponse rename(long userId, long conversationId, RenameConversationRequest request);

    /** 删除归属于当前用户的会话，并级联清除其问答正文。 */
    void delete(long userId, long conversationId);
}
