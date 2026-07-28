from dataclasses import dataclass

import pytest

from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.indexing_result import IndexingResult


class InMemoryDocumentStorage:
    def __init__(self, content: bytes) -> None:
        self._content = content

    async def download(self, source_url: str) -> bytes:
        return self._content


class FakeEmbedder:
    def __init__(self, dim: int = 4) -> None:
        self._dim = dim

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [[1.0] * self._dim for _ in texts]

    async def ready(self) -> bool:
        return True


class InMemoryVectorStore:
    def __init__(self) -> None:
        self.saved: list[tuple[Chunk, list[float]]] = []
        self.deleted: list[str] = []

    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        self.saved.extend(zip(chunks, vectors, strict=True))

    async def delete_by_document(self, document_id: str) -> None:
        self.deleted.append(document_id)
        self.saved = [(chunk, vector) for chunk, vector in self.saved if chunk.document_id != document_id]


class InMemoryCallback:
    def __init__(self) -> None:
        self.results: list[IndexingResult] = []

    async def report(self, result: IndexingResult) -> None:
        self.results.append(result)


class FailingVectorStore:
    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        raise RuntimeError("保存失败")

    async def delete_by_document(self, document_id: str) -> None:
        pass


@dataclass
class IndexingServiceFixture:
    service: IndexingService
    vector_store: InMemoryVectorStore
    callback: InMemoryCallback


@pytest.fixture
def fixture() -> IndexingServiceFixture:
    content = "第一段。\n\n第二段。".encode()
    vector_store = InMemoryVectorStore()
    callback = InMemoryCallback()
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        text_parser=TxtParser(),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FakeEmbedder(),
        vector_store=vector_store,
        callback=callback,
    )
    return IndexingServiceFixture(service, vector_store, callback)


@pytest.fixture
def command() -> IndexingCommand:
    return IndexingCommand(
        task_id="task-1",
        attempt=1,
        knowledge_base_id=1,
        document_id="doc-1",
        filename="test.txt",
        source_url="http://minio/test.txt",
        request_id="req-1",
    )


@pytest.mark.asyncio
async def test_index_success(command: IndexingCommand, fixture: IndexingServiceFixture) -> None:
    result = await fixture.service.index(command)

    assert result.success is True
    assert result.task_id == command.task_id
    assert result.attempt == command.attempt
    assert result.document_id == command.document_id
    assert result.chunks_count == 1
    assert len(fixture.vector_store.saved) == 1
    assert fixture.callback.results == [result]


@pytest.mark.asyncio
async def test_index_failure_triggers_cleanup(command: IndexingCommand) -> None:
    content = "第一段。\n\n第二段。".encode()
    callback = InMemoryCallback()
    failing_service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        text_parser=TxtParser(),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FakeEmbedder(),
        vector_store=FailingVectorStore(),
        callback=callback,
    )

    result = await failing_service.index(command)

    assert result.success is False
    assert callback.results == [result]
