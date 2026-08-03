from collections.abc import AsyncIterator

from sage_vault_rag.application.answering.cancellation import CancellationRegistry
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.events import (
    Completed,
    Delta,
    Failed,
    Refused,
    RetrievedChunkDiagnostic,
    Started,
    Stopped,
)
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.generation import GenerationPort
from sage_vault_rag.ports.vector_store import VectorStorePort


class InMemoryEmbedder(EmbeddingPort):
    def __init__(self, vector: list[float]) -> None:
        self._vector = vector

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._vector for _ in texts]

    async def ready(self) -> bool:
        return True


class InMemoryVectorStore(VectorStorePort):
    def __init__(self, chunks: list[tuple[int, RetrievedChunk]]) -> None:
        self._chunks = chunks

    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        pass

    async def delete_by_document(self, document_id: str) -> None:
        pass

    async def search(
        self,
        knowledge_base_id: int,
        vector: list[float],
        top_k: int,
    ) -> list[RetrievedChunk]:
        return [
            chunk for kb_id, chunk in self._chunks
            if kb_id == knowledge_base_id
        ][:top_k]


class CapturingGenerator(GenerationPort):
    def __init__(self, deltas: list[str]) -> None:
        self._deltas = deltas

    async def generate(
        self,
        generation_id: str,
        question: str,
        chunks: list[RetrievedChunk],
    ) -> AsyncIterator[str]:
        for delta in self._deltas:
            yield delta


async def test_empty_retrieval_returns_started_then_refused() -> None:
    service = AnsweringService(
        embedder=InMemoryEmbedder([0.1, 0.2]),
        vector_store=InMemoryVectorStore([]),
        generator=CapturingGenerator([]),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(1, "问题", "gen-1")]

    assert events == [Started("gen-1"), Refused("gen-1", "该知识库暂无可用文档")]


async def test_weak_evidence_returns_refused() -> None:
    chunks = [(1, RetrievedChunk("c1", "d1", "file.txt", 0, "无关内容", score=1.5))]
    service = AnsweringService(
        embedder=InMemoryEmbedder([0.1, 0.2]),
        vector_store=InMemoryVectorStore(chunks),
        generator=CapturingGenerator([]),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(1, "问题", "gen-1")]

    assert len(events) == 2
    assert isinstance(events[1], Refused)


async def test_sufficient_evidence_streams_deltas_and_completed() -> None:
    chunks = [(1, RetrievedChunk("c1", "d1", "file.txt", 0, "答案内容", score=0.3))]
    service = AnsweringService(
        embedder=InMemoryEmbedder([0.1, 0.2]),
        vector_store=InMemoryVectorStore(chunks),
        generator=CapturingGenerator(["答案", "内容"]),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(1, "问题", "gen-1")]

    assert events[0] == Started("gen-1")
    assert events[1] == Delta("gen-1", "答案")
    assert events[2] == Delta("gen-1", "内容")
    completed = events[3]
    assert isinstance(completed, Completed)
    assert completed.generation_id == "gen-1"
    # 检索诊断只携带标识与分数，不含片段正文。
    assert completed.retrieval_diagnostics == [
        RetrievedChunkDiagnostic(document_id="d1", chunk_id="c1", score=0.3)
    ]
    # 三个阶段的耗时都被采集，且为非负整数毫秒。
    assert set(completed.stage_durations) == {"embedding", "retrieval", "generation"}
    assert all(isinstance(v, int) and v >= 0 for v in completed.stage_durations.values())
    assert completed.model_request_id is None


async def test_cancel_mid_stream_ends_with_stopped_and_keeps_earlier_deltas() -> None:
    chunks = [(1, RetrievedChunk("c1", "d1", "file.txt", 0, "答案内容", score=0.3))]
    cancellations = CancellationRegistry()
    service = AnsweringService(
        embedder=InMemoryEmbedder([0.1, 0.2]),
        vector_store=InMemoryVectorStore(chunks),
        generator=CapturingGenerator(["第一段", "第二段", "第三段"]),
        top_k=3,
        refusal_threshold=1.0,
        cancellations=cancellations,
    )

    events = []
    async for event in service.answer(1, "问题", "gen-1"):
        events.append(event)
        if isinstance(event, Delta):
            cancellations.cancel("gen-1")

    assert events == [Started("gen-1"), Delta("gen-1", "第一段"), Stopped("gen-1")]


async def test_generation_is_untracked_after_stream_ends() -> None:
    chunks = [(1, RetrievedChunk("c1", "d1", "file.txt", 0, "答案内容", score=0.3))]
    cancellations = CancellationRegistry()
    service = AnsweringService(
        embedder=InMemoryEmbedder([0.1, 0.2]),
        vector_store=InMemoryVectorStore(chunks),
        generator=CapturingGenerator(["答案"]),
        top_k=3,
        refusal_threshold=1.0,
        cancellations=cancellations,
    )

    [event async for event in service.answer(1, "问题", "gen-1")]

    assert not cancellations.is_tracked("gen-1")
    assert cancellations.cancel("gen-1") is False


async def test_retrieval_filters_by_knowledge_base_id() -> None:
    chunks = [
        (1, RetrievedChunk("c1", "d1", "a.txt", 0, "知识库 A", score=0.1)),
        (2, RetrievedChunk("c2", "d2", "b.txt", 0, "知识库 B", score=0.1)),
    ]
    captured_chunks: list[RetrievedChunk] = []

    class EchoingGenerator(GenerationPort):
        async def generate(
            self,
            generation_id: str,
            question: str,
            chunks: list[RetrievedChunk],
        ) -> AsyncIterator[str]:
            captured_chunks.extend(chunks)
            for chunk in chunks:
                yield chunk.text

    service = AnsweringService(
        embedder=InMemoryEmbedder([0.1, 0.2]),
        vector_store=InMemoryVectorStore(chunks),
        generator=EchoingGenerator(),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(2, "问题", "gen-1")]

    assert len(captured_chunks) == 1
    assert captured_chunks[0].text == "知识库 B"
    assert isinstance(events[-1], Completed)


class FailingEmbedder(EmbeddingPort):
    def __init__(self, error: Exception) -> None:
        self._error = error

    async def embed(self, texts: list[str]) -> list[list[float]]:
        raise self._error

    async def ready(self) -> bool:
        return True


class CrashingGenerator(GenerationPort):
    def __init__(self, before: list[str], error: Exception) -> None:
        self._before = before
        self._error = error

    async def generate(
        self,
        generation_id: str,
        question: str,
        chunks: list[RetrievedChunk],
    ) -> AsyncIterator[str]:
        for delta in self._before:
            yield delta
        raise self._error


async def test_embedding_failure_emits_failed_with_masked_detail() -> None:
    service = AnsweringService(
        embedder=FailingEmbedder(RuntimeError("sk-ABC123DEF456 embedding backend 5xx")),
        vector_store=InMemoryVectorStore([]),
        generator=CapturingGenerator([]),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(1, "问题", "gen-1")]

    assert isinstance(events[-1], Failed)
    # 对外只暴露受控失败类别，绝不携带原始异常文本或密钥。
    assert events[-1].detail in {
        "embedding_failed",
        "retrieval_or_generation_failed",
        "vector_store_failed",
        "unexpected_failure",
    }
    assert "sk-ABC123DEF456" not in events[-1].detail


async def test_generation_crash_after_deltas_emits_failed_keeping_earlier_deltas() -> None:
    chunks = [(1, RetrievedChunk("c1", "d1", "file.txt", 0, "答案内容", score=0.3))]
    service = AnsweringService(
        embedder=InMemoryEmbedder([0.1, 0.2]),
        vector_store=InMemoryVectorStore(chunks),
        generator=CrashingGenerator(
            ["部分", "答案"], RuntimeError("bge-m3 model timeout during generation")
        ),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(1, "问题", "gen-1")]

    assert [type(e).__name__ for e in events] == [
        "Started",
        "Delta",
        "Delta",
        "Failed",
    ]
    assert isinstance(events[-1], Failed)
    assert events[-1].detail in {
        "retrieval_or_generation_failed",
        "embedding_failed",
        "vector_store_failed",
        "unexpected_failure",
    }
