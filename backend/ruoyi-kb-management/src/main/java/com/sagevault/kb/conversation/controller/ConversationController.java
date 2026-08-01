package com.sagevault.kb.conversation.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresLogin;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AnswerStateSnapshot;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.domain.CreateConversationRequest;
import com.sagevault.kb.conversation.domain.RenameConversationRequest;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.qarecord.domain.QaRecordResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @GetMapping
    @RequiresLogin
    public R<List<ConversationResponse>> list() {
        return R.ok(conversations.list(SecurityUtils.getUserId()));
    }

    @GetMapping("/{id}")
    @RequiresLogin
    public R<ConversationResponse> get(@PathVariable long id) {
        return R.ok(conversations.get(SecurityUtils.getUserId(), id));
    }

    @GetMapping("/{id}/questions")
    @RequiresLogin
    public R<List<QaRecordResponse>> history(@PathVariable long id) {
        return R.ok(conversations.history(SecurityUtils.getUserId(), id));
    }

    @PutMapping("/{id}/title")
    @RequiresLogin
    public R<ConversationResponse> rename(@PathVariable long id, @RequestBody RenameConversationRequest request) {
        return R.ok(conversations.rename(SecurityUtils.getUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequiresLogin
    public R<Void> delete(@PathVariable long id) {
        conversations.delete(SecurityUtils.getUserId(), id);
        return R.ok();
    }

    @PostMapping(value = "/{id}/questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresLogin
    public Flux<ServerSentEvent<AnswerEvent>> ask(@PathVariable long id, @RequestBody AskQuestionRequest request) {
        return conversations.askAndStream(SecurityUtils.getUserId(), id, request).map(event -> ServerSentEvent.builder(event)
                .event(eventName(event)).build());
    }

    @GetMapping("/{id}/answers/{generationId}")
    @RequiresLogin
    public R<AnswerStateSnapshot> answerState(@PathVariable long id, @PathVariable String generationId) {
        return R.ok(conversations.getAnswerState(SecurityUtils.getUserId(), id, generationId));
    }

    private static String eventName(AnswerEvent event) {
        if (event instanceof AnswerEvent.Started) {
            return "started";
        }
        if (event instanceof AnswerEvent.Delta) {
            return "delta";
        }
        if (event instanceof AnswerEvent.Completed) {
            return "completed";
        }
        return "refused";
    }
}
