package com.sagevault.kb.conversation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.impl.ConversationServiceImpl;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.qarecord.service.QaRecordService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ConversationServiceImplTest {
    @Test
    void marksUnfinishedWhenStreamCompletesWithoutTerminalEvent() {
        ConversationMapper mapper = mock(ConversationMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        QaRecordService records = mock(QaRecordService.class);
        RagAnswerPort rag = mock(RagAnswerPort.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(3L);
        conversation.setUserId(7L);
        conversation.setKnowledgeBaseId(11L);
        when(mapper.findById(3L)).thenReturn(conversation);
        doNothing().when(records).create(anyLong(), anyLong(), anyString(), anyString(), anyString());
        when(rag.answer(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Flux.just(new AnswerEvent.Started(invocation.getArgument(3))));
        when(documents.hasAvailableDocuments(anyLong())).thenReturn(true);
        ConversationService service = new ConversationServiceImpl(mapper, knowledgeBases, documents, records, rag);

        service.ask(7L, 3L, new AskQuestionRequest("问题", "request-1")).collectList().block();

        verify(records).markUnfinished(anyString());
    }

    @Test
    void rejectsBlankRequestIdBeforeCreatingRecord() {
        ConversationService service = new ConversationServiceImpl(mock(ConversationMapper.class),
                mock(KnowledgeBaseService.class), mock(DocumentService.class), mock(QaRecordService.class), mock(RagAnswerPort.class));

        assertThatThrownBy(() -> service.ask(7L, 3L, new AskQuestionRequest("问题", " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求标识不能为空");
    }
}
