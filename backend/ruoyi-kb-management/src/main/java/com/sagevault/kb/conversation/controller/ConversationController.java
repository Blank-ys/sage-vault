package com.sagevault.kb.conversation.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresLogin;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.service.ConversationService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    @RequiresLogin
    public R<ConversationResponse> create(@RequestBody CreateConversationRequest request) {
        return R.ok(conversations.create(SecurityUtils.getUserId(), request));
    }

    @PostMapping(value = "/{id}/questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresLogin
    public Flux<ServerSentEvent<AnswerEvent>> ask(@PathVariable long id, @RequestBody AskQuestionRequest request) {
        return conversations.ask(SecurityUtils.getUserId(), id, request).map(event -> ServerSentEvent.builder(event)
                .event(event instanceof AnswerEvent.Started ? "started" : "refused").build());
    }
}
