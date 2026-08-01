package com.sagevault.kb.conversation.service.port;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface RagAnswerPort {
    Flux<AnswerEvent> answer(long knowledgeBaseId, String question, String requestId, String generationId);

    /**
     * 尽力取消在途生成，调用必须路由回持有该生成的 RAG 实例。
     *
     * <p>该调用只是投递停止意图：终态由 Java 裁决，取消失败不改变业务结论。
     *
     * @return true 表示目标实例确认收到并已向该生成投递停止信号
     */
    Mono<Boolean> cancel(String generationId, String requestId);
}
