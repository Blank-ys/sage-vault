import asyncio
import logging
import time
import uuid
from collections.abc import AsyncIterator

from sage_vault_rag.application.answering.cancellation import CancellationRegistry
from sage_vault_rag.model.events import (
    AnswerEvent,
    Completed,
    Delta,
    Failed,
    Refused,
    RetrievedChunkDiagnostic,
    Started,
    Stopped,
)
from sage_vault_rag.model.privacy import mask_failure_detail, mask_sensitive
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.generation import GenerationPort
from sage_vault_rag.ports.vector_store import VectorStorePort

logger = logging.getLogger(__name__)

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
        # 跨语言追踪标识：同一 trace_id 同时出现在 Python 服务日志与 Java 网关错误日志，
        # 用于把浏览器看到的 Failed 事件关联到服务端具体诊断，而不把诊断本身泄漏出去。
        trace_id = uuid.uuid4().hex

        def _log_failure(stage: str, error: BaseException) -> str:
            # 诊断留在服务端，且日志先脱敏任何密钥/令牌。对外只发受控失败类别。
            logger.error(
                "Answer generation failed: trace=%s generation_id=%s knowledge_base_id=%s stage=%s error=%s",
                trace_id,
                generation_id,
                knowledge_base_id,
                stage,
                mask_sensitive(str(error)),
                exc_info=error,
            )
            return mask_failure_detail(error)

        with self._cancellations.track(generation_id) as cancelled:
            yield Started(generation_id)
            if cancelled.is_set():
                yield Stopped(generation_id)
                return
            stage_durations: dict[str, int] = {}
            try:
                embed_start = time.perf_counter()
                vectors = await self._embedder.embed([question])
                stage_durations["embedding"] = _ms_since(embed_start)
            except Exception as error:
                yield Failed(generation_id, _log_failure("embed", error))
                return
            retrieval_start = time.perf_counter()
            chunks = await self._vector_store.search(knowledge_base_id, vectors[0], self._top_k)
            stage_durations["retrieval"] = _ms_since(retrieval_start)
            if cancelled.is_set():
                yield Stopped(generation_id)
                return
            if not chunks:
                yield Refused(generation_id, EMPTY_KNOWLEDGE_BASE_MESSAGE)
                return
            if chunks[0].score > self._refusal_threshold:
                yield Refused(generation_id, WEAK_EVIDENCE_MESSAGE)
                return
            retrieval_diagnostics = [
                RetrievedChunkDiagnostic(
                    document_id=chunk.document_id,
                    chunk_id=chunk.chunk_id,
                    score=chunk.score,
                )
                for chunk in chunks
            ]
            deltas = self._generator.generate(generation_id, question, chunks)
            generation_start = time.perf_counter()
            try:
                async for delta in deltas:
                    if cancelled.is_set():
                        yield Stopped(generation_id)
                        return
                    yield Delta(generation_id, delta)
                    if cancelled.is_set():
                        yield Stopped(generation_id)
                        return
            except Exception as error:
                # 生成/检索流在已产出部分 delta 后崩溃：已产出的 delta 依然有效，
                # 但流程必须以 Failed 终止，不能留下空白或误导性的完成卡片。
                yield Failed(generation_id, _log_failure("generate", error))
                return
            finally:
                stage_durations["generation"] = _ms_since(generation_start)
                await _close_quietly(deltas)
            logger.info(
                "Answer completed: trace=%s generation_id=%s knowledge_base_id=%s "
                "retrieved=%d durations=%s",
                trace_id,
                generation_id,
                knowledge_base_id,
                len(retrieval_diagnostics),
                mask_sensitive(str(stage_durations)),
            )
            yield Completed(
                generation_id,
                retrieval_diagnostics=retrieval_diagnostics,
                stage_durations=stage_durations,
                model_request_id=None,
            )


def _ms_since(start: float) -> int:
    """perf_counter 起点到现在的毫秒耗时，向上取整为整数毫秒。"""
    return max(0, int((time.perf_counter() - start) * 1000))


async def _close_quietly(deltas: AsyncIterator[str]) -> None:
    aclose = getattr(deltas, "aclose", None)
    if aclose is None:
        return
    try:
        await aclose()
    except (asyncio.CancelledError, RuntimeError, StopAsyncIteration):
        return
