import asyncio
from collections.abc import AsyncIterator

from sage_vault_rag.application.answering.cancellation import CancellationRegistry
from sage_vault_rag.model.events import AnswerEvent, Completed, Delta, Refused, Started, Stopped
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.generation import GenerationPort
from sage_vault_rag.ports.vector_store import VectorStorePort

EMPTY_KNOWLEDGE_BASE_MESSAGE = "该知识库暂无可用文档"
WEAK_EVIDENCE_MESSAGE = "未找到足够相关的文档内容"


class AnsweringService:
    """检索式回答流程编排：嵌入问题、召回片段、拒答判断、流式生成。"""

    def __init__(
        self,
        embedder: EmbeddingPort,
        vector_store: VectorStorePort,
        generator: GenerationPort,
        top_k: int,
        refusal_threshold: float,
        cancellations: CancellationRegistry | None = None,
    ) -> None:
        self._embedder = embedder
        self._vector_store = vector_store
        self._generator = generator
        self._top_k = top_k
        self._refusal_threshold = refusal_threshold
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
        with self._cancellations.track(generation_id) as cancelled:
            yield Started(generation_id)
            if cancelled.is_set():
                yield Stopped(generation_id)
                return
            vectors = await self._embedder.embed([question])
            chunks = await self._vector_store.search(knowledge_base_id, vectors[0], self._top_k)
            if cancelled.is_set():
                yield Stopped(generation_id)
                return
            if not chunks:
                yield Refused(generation_id, EMPTY_KNOWLEDGE_BASE_MESSAGE)
                return
            if chunks[0].score > self._refusal_threshold:
                yield Refused(generation_id, WEAK_EVIDENCE_MESSAGE)
                return
            deltas = self._generator.generate(generation_id, question, chunks)
            try:
                async for delta in deltas:
                    if cancelled.is_set():
                        yield Stopped(generation_id)
                        return
                    yield Delta(generation_id, delta)
                    if cancelled.is_set():
                        yield Stopped(generation_id)
                        return
            finally:
                await _close_quietly(deltas)
            yield Completed(generation_id)


async def _close_quietly(deltas: AsyncIterator[str]) -> None:
    aclose = getattr(deltas, "aclose", None)
    if aclose is None:
        return
    try:
        await aclose()
    except (asyncio.CancelledError, RuntimeError, StopAsyncIteration):
        return
