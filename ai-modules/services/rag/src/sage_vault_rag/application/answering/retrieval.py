"""检索证据阶段：嵌入问题并召回片段，集中处理该阶段的计时与失败。

嵌入与检索共用同一失败语义：任一异常都被归约为携带原始异常与已脱敏类别的
``RetrievalFailure``，由编排层统一产出 Failed 事件；检索异常不再从编排的
事件流中逃逸，与生成阶段保持一致的失败语义。
"""

import time
from dataclasses import dataclass

from sage_vault_rag.application.answering.timing import ms_since
from sage_vault_rag.model.events import RetrievedChunkDiagnostic
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.vector_store import VectorStorePort


@dataclass(frozen=True)
class RetrievalOk:
    """检索成功：召回片段、检索诊断与各阶段毫秒耗时。"""

    chunks: list[RetrievedChunk]
    diagnostics: list[RetrievedChunkDiagnostic]
    durations: dict[str, int]


@dataclass(frozen=True)
class RetrievalFailure:
    """检索阶段失败；error 是原始异常，仅用于服务端日志与失败分类。"""

    stage: str
    error: BaseException
    durations: dict[str, int]


class EvidenceRetriever:
    """嵌入 + 检索，对外暴露单一检索结果接口。"""

    def __init__(
        self,
        embedder: EmbeddingPort,
        vector_store: VectorStorePort,
        top_k: int,
    ) -> None:
        self._embedder = embedder
        self._vector_store = vector_store
        self._top_k = top_k

    async def retrieve(self, knowledge_base_id: int, question: str) -> RetrievalOk | RetrievalFailure:
        durations: dict[str, int] = {}
        embed_start = time.perf_counter()
        try:
            vectors = await self._embedder.embed([question])
        except Exception as error:  # noqa: BLE001 适配器边界：嵌入异常统一映射为 RetrievalFailure
            durations["embedding"] = ms_since(embed_start)
            return RetrievalFailure("embed", error, durations)
        durations["embedding"] = ms_since(embed_start)

        retrieval_start = time.perf_counter()
        try:
            chunks = await self._vector_store.search(knowledge_base_id, vectors[0], self._top_k)
        except Exception as error:  # noqa: BLE001 适配器边界：检索异常统一映射为 RetrievalFailure
            durations["retrieval"] = ms_since(retrieval_start)
            return RetrievalFailure("retrieve", error, durations)
        durations["retrieval"] = ms_since(retrieval_start)

        diagnostics = [
            RetrievedChunkDiagnostic(
                document_id=chunk.document_id,
                chunk_id=chunk.chunk_id,
                score=chunk.score,
            )
            for chunk in chunks
        ]
        return RetrievalOk(chunks, diagnostics, durations)
