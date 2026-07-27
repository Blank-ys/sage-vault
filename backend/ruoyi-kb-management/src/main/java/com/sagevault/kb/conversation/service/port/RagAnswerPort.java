package com.sagevault.kb.conversation.service.port;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import reactor.core.publisher.Flux;

public interface RagAnswerPort {
    Flux<AnswerEvent> answer(long knowledgeBaseId, String question, String requestId, String generationId);
}
