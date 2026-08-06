package com.sagevault.kb.conversation.service;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AnswerStateSnapshot;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import reactor.core.publisher.Flux;

/**
 * 会话回答生命周期的窄公开 interface。只暴露开始并流式返回、状态查询与显式停止；
 * 会话归属校验、持久化裁决、停止信号与 RAG 取消都留在实现内部。
 */
public interface AnswerSessionService {
    /**
     * 发起一次问答并流式返回增量。同一会话在任意时刻只允许一个进行中的回答；
     * 若已有进行中的回答，抛出并发冲突异常。方法内部已处理串行化、状态机与结果落库。
     */
    Flux<AnswerEvent> askAndStream(long userId, long conversationId, AskQuestionRequest request);

    /** 读取某次回答的当前状态与终态结果，供轮询或懒加载使用。 */
    AnswerStateSnapshot getAnswerState(long userId, long conversationId, String generationId);

    /**
     * 用户显式停止某次进行中的回答。停止是业务命令：Java 先裁决终态为已停止并保留残缺正文，
     * 再尽力通知 Python 停止生成；通知失败不改变已裁决的终态。重复停止或对已终态回答调用会失败。
     */
    AnswerStateSnapshot stopAnswer(long userId, long conversationId, String generationId);
}
