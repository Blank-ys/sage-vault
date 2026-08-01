package com.sagevault.kb.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AnswerStateSnapshot;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.impl.ConversationServiceImpl;
import com.sagevault.kb.conversation.service.port.ConversationAudit;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
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
        when(mapper.selectForStreaming(3L, 7L)).thenReturn(conversation);
        doNothing().when(records).create(anyLong(), anyLong(), anyString(), anyString(), anyString());
        when(records.hasPending(anyLong())).thenReturn(false);
        when(rag.answer(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Flux.just(new AnswerEvent.Started(invocation.getArgument(3))));
        when(documents.hasAvailableDocuments(anyLong())).thenReturn(true);
        ConversationService service = new ConversationServiceImpl(mapper, knowledgeBases, documents, records, rag,
                mock(ConversationAudit.class));

        service.askAndStream(7L, 3L, new AskQuestionRequest("问题", "request-1")).collectList().block();

        verify(records).markUnfinished(anyString());
    }

    @Test
    void rejectsBlankRequestIdBeforeCreatingRecord() {
        ConversationService service = new ConversationServiceImpl(mock(ConversationMapper.class),
                mock(KnowledgeBaseService.class), mock(DocumentService.class), mock(QaRecordService.class),
                mock(RagAnswerPort.class), mock(ConversationAudit.class));

        assertThatThrownBy(() -> service.askAndStream(7L, 3L, new AskQuestionRequest("问题", " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求标识不能为空");
    }

    @Test
    void refusesSecondAnswerWhileOneIsInProgress() {
        ConversationMapper mapper = mock(ConversationMapper.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        QaRecordService records = mock(QaRecordService.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(3L);
        conversation.setUserId(7L);
        conversation.setKnowledgeBaseId(11L);
        when(mapper.findById(3L)).thenReturn(conversation);
        when(mapper.selectForStreaming(3L, 7L)).thenReturn(conversation);
        when(records.hasPending(3L)).thenReturn(true);
        when(documents.hasAvailableDocuments(anyLong())).thenReturn(true);
        ConversationService service = new ConversationServiceImpl(mapper, knowledgeBases, documents, records,
                mock(RagAnswerPort.class), mock(ConversationAudit.class));

        assertThatThrownBy(() -> service.askAndStream(7L, 3L, new AskQuestionRequest("问题", "request-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有进行中的回答");
    }

    @Test
    void getAnswerStateSurfacesCompletedAnswer() {
        ConversationMapper mapper = mock(ConversationMapper.class);
        QaRecordService records = mock(QaRecordService.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(3L);
        conversation.setUserId(7L);
        conversation.setKnowledgeBaseId(11L);
        when(mapper.findById(3L)).thenReturn(conversation);
        QaRecordEntity record = new QaRecordEntity();
        record.setConversationId(3L);
        record.setStatus(QaRecordStatus.COMPLETED);
        record.setAnswer("最终答案");
        when(records.findByGenerationId("gen-1")).thenReturn(record);
        ConversationService service = new ConversationServiceImpl(mapper, mock(KnowledgeBaseService.class),
                mock(DocumentService.class), records, mock(RagAnswerPort.class), mock(ConversationAudit.class));

        AnswerStateSnapshot snapshot = service.getAnswerState(7L, 3L, "gen-1");

        assertThat(snapshot.ready()).isTrue();
        assertThat(snapshot.status()).isEqualTo(QaRecordStatus.COMPLETED);
        assertThat(snapshot.answer()).isEqualTo("最终答案");
    }

    @Test
    void getAnswerStateReportsInProgressAsNotReady() {
        ConversationMapper mapper = mock(ConversationMapper.class);
        QaRecordService records = mock(QaRecordService.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(3L);
        conversation.setUserId(7L);
        conversation.setKnowledgeBaseId(11L);
        when(mapper.findById(3L)).thenReturn(conversation);
        QaRecordEntity record = new QaRecordEntity();
        record.setConversationId(3L);
        record.setStatus(QaRecordStatus.STARTED);
        record.setAnswer("");
        when(records.findByGenerationId("gen-2")).thenReturn(record);
        ConversationService service = new ConversationServiceImpl(mapper, mock(KnowledgeBaseService.class),
                mock(DocumentService.class), records, mock(RagAnswerPort.class), mock(ConversationAudit.class));

        AnswerStateSnapshot snapshot = service.getAnswerState(7L, 3L, "gen-2");

        assertThat(snapshot.ready()).isFalse();
        assertThat(snapshot.answer()).isNull();
    }
}
