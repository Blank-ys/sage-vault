package com.sagevault.kb.conversation.service;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import reactor.core.publisher.Flux;

public interface ConversationService {
    ConversationResponse create(long userId, CreateConversationRequest request);
    Flux<AnswerEvent> ask(long userId, long conversationId, AskQuestionRequest request);
}
