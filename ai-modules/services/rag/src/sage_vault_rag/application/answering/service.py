import uuid
from collections.abc import AsyncIterator
from contextlib import aclosing

from sage_vault_rag.application.answering.cancellation import CancellationRegistry
from sage_vault_rag.application.answering.diagnostics import AnswerDiagnostics
from sage_vault_rag.application.answering.generation import GenerationLifecycle
from sage_vault_rag.application.answering.refusal import RefusalPolicy
from sage_vault_rag.application.answering.retrieval import EvidenceRetriever, RetrievalFailure
from sage_vault_rag.model.events import (
    AnswerEvent,
    Completed,
    Failed,
    Refused,
    Started,
    Stopped,
)
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.generation import GenerationPort
from sage_vault_rag.ports.vector_store import VectorStorePort


class AnsweringService:
    """检索式回答流程编排：嵌入问题、召回片段、拒答判断、流式生成。

    对外保持单一 ``answer()`` 事件流接口；检索、证据判定、生成生命周期与
    取消登记各自收敛为内部 seam（retrieval / refusal / generation / cancellation）。
    """

    def __init__(
        self,
        embedder: EmbeddingPort,
        vector_store: VectorStorePort,
        generator: GenerationPort,
        top_k: int,
        refusal_threshold: float,
        cancellations: CancellationRegistry | None = None,
    ) -> None:
        self._retriever = EvidenceRetriever(embedder, vector_store, top_k)
        self._refusal_policy = RefusalPolicy(refusal_threshold)
        self._generation = GenerationLifecycle(generator)
        self._cancellations = cancellations or CancellationRegistry()

    @property
    def cancellations(self) -> CancellationRegistry:
        return self._cancellations

    async def answer(
        self,
        knowledge_base_id: int,
        question: str,
        generation_id: str,
    ) -> AsyncIterator[AnswerEvent]:
        trace_id = uuid.uuid4().hex
        diagnostics = AnswerDiagnostics(trace_id, generation_id, knowledge_base_id)

        with self._cancellations.track(generation_id) as cancelled:
            yield Started(generation_id)
            if cancelled.is_set():
                yield Stopped(generation_id)
                return

            outcome = await self._retriever.retrieve(knowledge_base_id, question)
            if isinstance(outcome, RetrievalFailure):
                yield Failed(generation_id, diagnostics.failure(outcome.stage, outcome.error))
                return
            if cancelled.is_set():
                yield Stopped(generation_id)
                return

            refusal_message = self._refusal_policy.judge(outcome.chunks)
            if refusal_message is not None:
                yield Refused(generation_id, refusal_message)
                return

            durations = dict(outcome.durations)
            async with aclosing(
                self._generation.stream(
                    generation_id,
                    question,
                    outcome.chunks,
                    cancelled,
                    durations,
                    diagnostics,
                )
            ) as events:
                async for event in events:
                    yield event
                    if isinstance(event, (Stopped, Failed)):
                        return

            diagnostics.completed(len(outcome.diagnostics), durations)
            yield Completed(
                generation_id,
                retrieval_diagnostics=outcome.diagnostics,
                stage_durations=durations,
                model_request_id=None,
            )
