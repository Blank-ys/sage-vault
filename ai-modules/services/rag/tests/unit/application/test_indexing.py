from dataclasses import dataclass

import pytest

from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.document_parser.dispatcher import FormatDispatchingDocumentParser
from sage_vault_rag.adapters.markdown_parser.parser import MarkdownParser
from sage_vault_rag.adapters.pdf_parser.parser import PdfParser
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.indexing_result import IndexingResult
from sage_vault_rag.model.parsed_document import ParsedDocument
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk
from tests._pdf_fixtures import make_encrypted_pdf


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

    async def search(
        self,
        knowledge_base_id: int,
        vector: list[float],
        top_k: int,
    ) -> list[RetrievedChunk]:
        return []


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

    async def search(
        self,
        knowledge_base_id: int,
        vector: list[float],
        top_k: int,
    ) -> list[RetrievedChunk]:
        return []


class FailingChunker:
    """切块阶段抛出异常，用于验证切块失败仍触发清理与失败回调。"""

    def split(
        self,
        document: ParsedDocument,
        knowledge_base_id: int,
        document_id: str,
        filename: str,
    ) -> list[Chunk]:
        raise RuntimeError("切块失败")


class FailingEmbedder:
    """嵌入阶段抛出异常，用于验证嵌入失败仍触发清理与失败回调。"""

    async def embed(self, texts: list[str]) -> list[list[float]]:
        raise RuntimeError("嵌入失败")

    async def ready(self) -> bool:
        return True


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
        document_parser=TxtParser(),
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
async def test_index_propagates_section_title_from_markdown_to_chunks() -> None:
    """03e：MD 文档入库后，保存到向量库的 Chunk 应携带 section_title 元数据。"""
    content = "# 总则\n\n第一条 为规范公司知识管理，制定本办法。".encode()
    vector_store = InMemoryVectorStore()
    callback = InMemoryCallback()
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        document_parser=FormatDispatchingDocumentParser({"md": MarkdownParser()}),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FakeEmbedder(),
        vector_store=vector_store,
        callback=callback,
    )
    md_command = IndexingCommand(
        task_id="task-md-meta",
        attempt=1,
        knowledge_base_id=1,
        document_id="doc-md-meta",
        filename="regulations.md",
        source_url="http://minio/regulations.md",
        request_id="req-md-meta",
    )

    result = await service.index(md_command)

    assert result.success is True
    assert len(vector_store.saved) >= 1
    for chunk, _ in vector_store.saved:
        assert chunk.section_title == "总则"
        assert chunk.page_number is None


@pytest.mark.asyncio
async def test_index_failure_triggers_cleanup(command: IndexingCommand) -> None:
    content = "第一段。\n\n第二段。".encode()
    callback = InMemoryCallback()
    failing_service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        document_parser=TxtParser(),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FakeEmbedder(),
        vector_store=FailingVectorStore(),
        callback=callback,
    )

    result = await failing_service.index(command)

    assert result.success is False
    assert callback.results == [result]


@pytest.mark.asyncio
async def test_index_md_failure_triggers_cleanup_and_callback(command: IndexingCommand) -> None:
    """MD 空文档应使 IndexingService 走失败路径：清理 + 回调 success=False。

    走 FormatDispatchingDocumentParser（与生产装配一致），验证扩展名分发后
    MarkdownParser 抛出的 ValueError 经 IndexingService 映射为失败结果。
    """
    callback = InMemoryCallback()
    vector_store = InMemoryVectorStore()

    service = IndexingService(
        document_storage=InMemoryDocumentStorage(b""),
        document_parser=FormatDispatchingDocumentParser({"md": MarkdownParser()}),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FakeEmbedder(),
        vector_store=vector_store,
        callback=callback,
    )
    md_command = IndexingCommand(
        task_id="task-md",
        attempt=1,
        knowledge_base_id=1,
        document_id="doc-md",
        filename="empty.md",
        source_url="http://minio/empty.md",
        request_id="req-md",
    )

    result = await service.index(md_command)

    assert result.success is False
    assert result.chunks_count == 0
    assert result.diagnostics["error"] == "ValueError"
    assert result.diagnostics["filename"] == "empty.md"
    assert vector_store.saved == []
    assert vector_store.deleted == ["doc-md", "doc-md"]
    assert callback.results == [result]


@pytest.mark.asyncio
async def test_index_pdf_failure_triggers_cleanup_and_callback() -> None:
    """加密 PDF 应使 IndexingService 走失败路径：清理 + 回调 success=False。

    走 FormatDispatchingDocumentParser（与生产装配一致），验证扩展名分发后
    PdfParser 抛出的 ValueError 经 IndexingService 映射为失败结果，
    不向 Milvus 写入任何片段。
    """
    callback = InMemoryCallback()
    vector_store = InMemoryVectorStore()
    encrypted_content = make_encrypted_pdf("机密内容")

    service = IndexingService(
        document_storage=InMemoryDocumentStorage(encrypted_content),
        document_parser=FormatDispatchingDocumentParser({"pdf": PdfParser()}),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FakeEmbedder(),
        vector_store=vector_store,
        callback=callback,
    )
    pdf_command = IndexingCommand(
        task_id="task-pdf",
        attempt=1,
        knowledge_base_id=1,
        document_id="doc-pdf",
        filename="secret.pdf",
        source_url="http://minio/secret.pdf",
        request_id="req-pdf",
    )

    result = await service.index(pdf_command)

    assert result.success is False
    assert result.chunks_count == 0
    assert result.diagnostics["error"] == "ValueError"
    assert result.diagnostics["filename"] == "secret.pdf"
    assert vector_store.saved == []
    assert vector_store.deleted == ["doc-pdf", "doc-pdf"]
    assert callback.results == [result]


@pytest.mark.asyncio
async def test_index_clears_stale_vectors_before_retry() -> None:
    """重试入库前先清理上次尝试残留的向量，确保原子发布。

    模拟前次失败尝试遗留的向量仍存在于向量库，重试时应先删除再写入，
    最终只保留本次成功写入的一套完整片段。
    """
    content = "第一段。\n\n第二段。".encode()
    vector_store = InMemoryVectorStore()
    callback = InMemoryCallback()

    stale_chunk = Chunk(
        chunk_id="stale-chunk-1",
        knowledge_base_id=1,
        document_id="doc-retry",
        filename="old.txt",
        sequence=0,
        text="过期内容",
    )
    await vector_store.save_chunks([stale_chunk], [[0.5] * 4])

    service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        document_parser=TxtParser(),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FakeEmbedder(),
        vector_store=vector_store,
        callback=callback,
    )
    retry_command = IndexingCommand(
        task_id="task-retry",
        attempt=2,
        knowledge_base_id=1,
        document_id="doc-retry",
        filename="test.txt",
        source_url="http://minio/test.txt",
        request_id="req-retry",
    )

    result = await service.index(retry_command)

    assert result.success is True
    assert result.attempt == 2
    assert vector_store.deleted == ["doc-retry"]
    remaining_stale = [c for c, _ in vector_store.saved if c.chunk_id == "stale-chunk-1"]
    assert remaining_stale == []
    fresh = [c for c, _ in vector_store.saved if c.document_id == "doc-retry"]
    assert len(fresh) == 1
    assert fresh[0].filename == "test.txt"


@pytest.mark.asyncio
async def test_index_chunk_failure_triggers_cleanup_and_callback(command: IndexingCommand) -> None:
    """切块阶段失败应触发清理与失败回调，不写入任何片段。

    验证 05 各阶段失败覆盖：切块失败时 IndexingService 走失败路径，
    先删除残留向量（入库前 + 清理时各一次），回调 success=False，
    diagnostics 包含异常类型与文件名。
    """
    callback = InMemoryCallback()
    vector_store = InMemoryVectorStore()

    service = IndexingService(
        document_storage=InMemoryDocumentStorage("第一段。".encode()),
        document_parser=TxtParser(),
        chunker=FailingChunker(),
        embedder=FakeEmbedder(),
        vector_store=vector_store,
        callback=callback,
    )

    result = await service.index(command)

    assert result.success is False
    assert result.chunks_count == 0
    assert result.diagnostics["error"] == "RuntimeError"
    assert result.diagnostics["filename"] == "test.txt"
    assert vector_store.saved == []
    assert vector_store.deleted == ["doc-1", "doc-1"]
    assert callback.results == [result]


@pytest.mark.asyncio
async def test_index_embed_failure_triggers_cleanup_and_callback(command: IndexingCommand) -> None:
    """嵌入阶段失败应触发清理与失败回调，不写入任何片段。

    验证 05 各阶段失败覆盖：嵌入失败时 IndexingService 走失败路径，
    先删除残留向量（入库前 + 清理时各一次），回调 success=False。
    """
    callback = InMemoryCallback()
    vector_store = InMemoryVectorStore()

    service = IndexingService(
        document_storage=InMemoryDocumentStorage("第一段。".encode()),
        document_parser=TxtParser(),
        chunker=ParagraphChunker(chunk_size=512, chunk_overlap=64),
        embedder=FailingEmbedder(),
        vector_store=vector_store,
        callback=callback,
    )

    result = await service.index(command)

    assert result.success is False
    assert result.chunks_count == 0
    assert result.diagnostics["error"] == "RuntimeError"
    assert result.diagnostics["filename"] == "test.txt"
    assert vector_store.saved == []
    assert vector_store.deleted == ["doc-1", "doc-1"]
    assert callback.results == [result]
