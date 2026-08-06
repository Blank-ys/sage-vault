package com.sagevault.kb.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.domain.AnswerStateSnapshot;
import com.sagevault.kb.conversation.domain.AskQuestionRequest;
import com.sagevault.kb.conversation.domain.ConversationEntity;
import com.sagevault.kb.conversation.mapper.ConversationMapper;
import com.sagevault.kb.conversation.service.impl.AnswerSessionServiceImpl;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.service.QaRecordService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class AnswerSessionServiceImplTest {
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
        AnswerSessionService service = new AnswerSessionServiceImpl(mapper, knowledgeBases, documents, records, rag);

        service.askAndStream(7L, 3L, new AskQuestionRequest("问题", "request-1")).collectList().block();

        verify(records).markUnfinished(anyString());
    }

    @Test
    void rejectsBlankRequestIdBeforeCreatingRecord() {
        AnswerSessionService service = new AnswerSessionServiceImpl(mock(ConversationMapper.class),
                mock(KnowledgeBaseService.class), mock(DocumentService.class), mock(QaRecordService.class),
                mock(RagAnswerPort.class));

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
        AnswerSessionService service = new AnswerSessionServiceImpl(mapper, knowledgeBases, documents, records,
                mock(RagAnswerPort.class));

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
        AnswerSessionService service = new AnswerSessionServiceImpl(mapper, mock(KnowledgeBaseService.class),
                mock(DocumentService.class), records, mock(RagAnswerPort.class));

        AnswerStateSnapshot snapshot = service.getAnswerState(7L, 3L, "gen-1");

        assertThat(snapshot.ready()).isTrue();
        assertThat(snapshot.status()).isEqualTo(QaRecordStatus.COMPLETED);
        assertThat(snapshot.answer()).isEqualTo("最终答案");
    }

    @Test
    void stopEndsTheLiveStreamWithAStoppedEventAndKeepsTheDeltasAlreadySent() {
        Fixture fixture = Fixture.streaming();
        Sinks.Many<AnswerEvent> upstream = Sinks.many().unicast().onBackpressureBuffer();
        when(fixture.rag.answer(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(upstream.asFlux());
        when(fixture.records.markStopped(anyString())).thenReturn(true);
        when(fixture.rag.cancel(anyString(), anyString())).thenReturn(Mono.just(true));

        List<AnswerEvent> received = new ArrayList<>();
        Flux<AnswerEvent> stream = fixture.service.askAndStream(7L, 3L, new AskQuestionRequest("问题", "request-1"));
        Disposable subscription = stream.subscribe(received::add);
        String generationId = fixture.startedGenerationId();
        upstream.tryEmitNext(new AnswerEvent.Started(generationId));
        upstream.tryEmitNext(new AnswerEvent.Delta(generationId, "已经生成的部分"));

        fixture.service.stopAnswer(7L, 3L, generationId);

        assertThat(received).last().isInstanceOf(AnswerEvent.Stopped.class);
        assertThat(received).anySatisfy(event -> assertThat(event)
                .isEqualTo(new AnswerEvent.Delta(generationId, "已经生成的部分")));
        verify(fixture.records).appendAnswer(generationId, "已经生成的部分");
        verify(fixture.records).markStopped(generationId);
        verify(fixture.records, never()).markUnfinished(anyString());
        verify(fixture.rag).cancel(eq(generationId), anyString());
        subscription.dispose();
    }

    @Test
    void stopSurvivesABestEffortCancelFailureBecauseJavaOwnsTheTerminalState() {
        Fixture fixture = Fixture.streaming();
        Sinks.Many<AnswerEvent> upstream = Sinks.many().unicast().onBackpressureBuffer();
        when(fixture.rag.answer(anyLong(), anyString(), anyString(), anyString())).thenReturn(upstream.asFlux());
        when(fixture.records.markStopped(anyString())).thenReturn(true);
        when(fixture.rag.cancel(anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("rag unreachable")));

        Disposable subscription = fixture.service
                .askAndStream(7L, 3L, new AskQuestionRequest("问题", "request-1")).subscribe();
        String generationId = fixture.startedGenerationId();
        stubTerminalRecord(fixture, generationId, QaRecordStatus.STOPPED, "已经生成的部分");

        AnswerStateSnapshot snapshot = fixture.service.stopAnswer(7L, 3L, generationId);

        assertThat(snapshot.status()).isEqualTo(QaRecordStatus.STOPPED);
        assertThat(snapshot.answer()).isEqualTo("已经生成的部分");
        subscription.dispose();
    }

    @Test
    void failedEventDecidesFailedTerminalStateWithoutLeakingRawDiagnostics() {
        Fixture fixture = Fixture.streaming();
        Sinks.Many<AnswerEvent> upstream = Sinks.many().unicast().onBackpressureBuffer();
        when(fixture.rag.answer(anyLong(), anyString(), anyString(), anyString())).thenReturn(upstream.asFlux());

        List<AnswerEvent> received = new ArrayList<>();
        Disposable subscription = fixture.service
                .askAndStream(7L, 3L, new AskQuestionRequest("问题", "request-1")).subscribe(received::add);
        String generationId = fixture.startedGenerationId();
        upstream.tryEmitNext(new AnswerEvent.Started(generationId));
        upstream.tryEmitNext(new AnswerEvent.Delta(generationId, "已经生成的部分"));
        // 生成中途失败：detail 为脱敏后的受控失败类别。
        upstream.tryEmitNext(new AnswerEvent.Failed(generationId, "retrieval_or_generation_failed"));
        subscription.dispose();

        assertThat(received).last().isInstanceOf(AnswerEvent.Failed.class);
        verify(fixture.records).markFailed(eq(generationId), eq("retrieval_or_generation_failed"));
        verify(fixture.records, never()).markUnfinished(anyString());
        // 失败后不向 Python 发取消：Java 已是终态 owner，且 Python 已自我终止。
        verify(fixture.rag, never()).cancel(anyString(), anyString());
    }

    @Test
    void stopIsRejectedOnceTheAnswerAlreadyReachedATerminalState() {
        Fixture fixture = Fixture.streaming();
        stubTerminalRecord(fixture, "gen-done", QaRecordStatus.COMPLETED, "最终答案");
        when(fixture.records.markStopped("gen-done")).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.stopAnswer(7L, 3L, "gen-done"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("该回答已结束，无法停止");

        verify(fixture.rag, never()).cancel(anyString(), anyString());
    }

    @Test
    void disconnectMarksUnfinishedWithoutCancellingTheRagGeneration() {
        Fixture fixture = Fixture.streaming();
        Sinks.Many<AnswerEvent> upstream = Sinks.many().unicast().onBackpressureBuffer();
        when(fixture.rag.answer(anyLong(), anyString(), anyString(), anyString())).thenReturn(upstream.asFlux());

        Disposable subscription = fixture.service
                .askAndStream(7L, 3L, new AskQuestionRequest("问题", "request-1")).subscribe();
        String generationId = fixture.startedGenerationId();
        subscription.dispose();

        verify(fixture.records).markUnfinished(generationId);
        verify(fixture.rag, never()).cancel(anyString(), anyString());
    }

    private static void stubTerminalRecord(Fixture fixture, String generationId, QaRecordStatus status,
            String answer) {
        QaRecordEntity record = new QaRecordEntity();
        record.setConversationId(3L);
        record.setStatus(status);
        record.setAnswer(answer);
        when(fixture.records.findByGenerationId(generationId)).thenReturn(record);
    }

    private record Fixture(AnswerSessionService service, QaRecordService records, RagAnswerPort rag,
            ArgumentCaptor<String> generationIds) {
        private static Fixture streaming() {
            ConversationMapper mapper = mock(ConversationMapper.class);
            QaRecordService records = mock(QaRecordService.class);
            RagAnswerPort rag = mock(RagAnswerPort.class);
            DocumentService documents = mock(DocumentService.class);
            ConversationEntity conversation = new ConversationEntity();
            conversation.setId(3L);
            conversation.setUserId(7L);
            conversation.setKnowledgeBaseId(11L);
            when(mapper.findById(3L)).thenReturn(conversation);
            when(mapper.selectForStreaming(3L, 7L)).thenReturn(conversation);
            when(records.hasPending(anyLong())).thenReturn(false);
            when(documents.hasAvailableDocuments(anyLong())).thenReturn(true);
            AnswerSessionService service = new AnswerSessionServiceImpl(mapper, mock(KnowledgeBaseService.class),
                    documents, records, rag);
            return new Fixture(service, records, rag, ArgumentCaptor.forClass(String.class));
        }

        private String startedGenerationId() {
            verify(records).create(anyLong(), anyLong(), anyString(), generationIds.capture(), anyString());
            String generationId = generationIds.getValue();
            QaRecordEntity record = new QaRecordEntity();
            record.setConversationId(3L);
            record.setStatus(QaRecordStatus.STARTED);
            record.setAnswer("");
            when(records.findByGenerationId(generationId)).thenReturn(record);
            return generationId;
        }
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
        AnswerSessionService service = new AnswerSessionServiceImpl(mapper, mock(KnowledgeBaseService.class),
                mock(DocumentService.class), records, mock(RagAnswerPort.class));

        AnswerStateSnapshot snapshot = service.getAnswerState(7L, 3L, "gen-2");

        assertThat(snapshot.ready()).isFalse();
        assertThat(snapshot.answer()).isNull();
    }
}
