from collections.abc import AsyncIterator

from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.events import Completed, Delta, Refused, Started
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

    assert events == [
        Started("gen-1"),
        Delta("gen-1", "答案"),
        Delta("gen-1", "内容"),
        Completed("gen-1"),
    ]


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
