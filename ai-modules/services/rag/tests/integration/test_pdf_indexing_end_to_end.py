import logging
import os
import uuid
from collections.abc import Generator

import pytest

from sage_vault_rag.adapters.bge_m3.embedder import BgeM3Embedder
from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.document_parser.dispatcher import FormatDispatchingDocumentParser
from sage_vault_rag.adapters.fake_generation.generator import FakeGenerationAdapter
from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.adapters.pdf_parser.parser import PdfParser
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.model.events import Completed, Delta
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.indexing_result import IndexingResult
from sage_vault_rag.ports.callback import CallbackPort
from sage_vault_rag.ports.document_storage import DocumentStoragePort
from tests._pdf_fixtures import make_encrypted_pdf, make_text_pdf


class InMemoryDocumentStorage(DocumentStoragePort):
    def __init__(self, content: bytes) -> None:
        self._content = content

    async def download(self, source_url: str) -> bytes:
        return self._content


class InMemoryCallback(CallbackPort):
    def __init__(self) -> None:
        self.results: list[IndexingResult] = []

    async def report(self, result: IndexingResult) -> None:
        self.results.append(result)


@pytest.fixture(scope="module")
def embedder() -> BgeM3Embedder:
    model_path = os.environ.get("SAGE_VAULT_RAG_EMBEDDING_MODEL_PATH")
    if not model_path:
        pytest.skip("未设置 SAGE_VAULT_RAG_EMBEDDING_MODEL_PATH")
    return BgeM3Embedder(
        model_path=model_path,
        profile="cpu-dev",
        batch_size=2,
    )


@pytest.fixture
def milvus_store() -> Generator[MilvusVectorStore]:
    host = os.environ.get("SAGE_VAULT_RAG_MILVUS_HOST", "127.0.0.1")
    port = int(os.environ.get("SAGE_VAULT_RAG_MILVUS_PORT", "19530"))
    store = MilvusVectorStore(
        host=host,
        port=port,
        collection_name=f"verify_pdf_indexing_{uuid.uuid4().hex[:8]}",
        vector_dim=1024,
    )
    yield store
    try:
        store._get_collection()
        from pymilvus import utility

        utility.drop_collection(store._collection_name, using=store._alias)
    except Exception:
        logging.getLogger(__name__).debug("清理测试 collection 失败", exc_info=True)


@pytest.fixture
def chunker() -> ParagraphChunker:
    return ParagraphChunker(chunk_size=64, chunk_overlap=8)


@pytest.fixture
def document_parser() -> FormatDispatchingDocumentParser:
    return FormatDispatchingDocumentParser(
        {
            "pdf": PdfParser(),
        }
    )


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_pdf_indexing_publishes_chunks_with_page_numbers(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> None:
    """PDF 文档入库后，Milvus 中应能观察到包含中文内容的片段。"""
    content = make_text_pdf("知识库管理办法")
    document_id = f"doc-pdf-{uuid.uuid4().hex[:8]}"
    knowledge_base_id = 70

    callback = InMemoryCallback()
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        document_parser=document_parser,
        chunker=chunker,
        embedder=embedder,
        vector_store=milvus_store,
        callback=callback,
    )
    command = IndexingCommand(
        task_id=f"verify-{document_id}",
        attempt=1,
        knowledge_base_id=knowledge_base_id,
        document_id=document_id,
        filename="regulations.pdf",
        source_url="http://minio/regulations.pdf",
        request_id=f"req-{document_id}",
    )
    result = await service.index(command)

    assert result.success is True
    assert result.chunks_count >= 1
    assert await milvus_store.count_by_document(command.document_id) == result.chunks_count
    assert callback.results == [result]

    collection = milvus_store._get_collection()
    collection.load()
    rows = collection.query(
        expr=milvus_store._document_expr(command.document_id),
        output_fields=["sequence", "text"],
    )
    rows.sort(key=lambda row: row["sequence"])
    full_text = "\n\n".join(row["text"] for row in rows)
    assert "知识库管理办法" in full_text

    # 03e：通过 search 验证来源元数据在召回结果中可观察
    # PDF 解析器为片段填充 page_number（从 1 开始），section_title 始终为 None
    query_vector = (await embedder.embed(["知识库管理办法"]))[0]
    retrieved = await milvus_store.search(knowledge_base_id, query_vector, top_k=10)
    assert len(retrieved) >= 1
    for chunk in retrieved:
        assert chunk.page_number == 1
        assert chunk.section_title is None


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_pdf_indexing_enables_recall_in_qa(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> None:
    """上传 PDF 后问答能召回中文内容。"""
    content = make_text_pdf("员工福利包括带薪年假和健康体检")
    document_id = f"doc-pdf-qa-{uuid.uuid4().hex[:8]}"
    knowledge_base_id = 80

    callback = InMemoryCallback()
    indexing_service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        document_parser=document_parser,
        chunker=chunker,
        embedder=embedder,
        vector_store=milvus_store,
        callback=callback,
    )
    command = IndexingCommand(
        task_id=f"verify-{document_id}",
        attempt=1,
        knowledge_base_id=knowledge_base_id,
        document_id=document_id,
        filename="benefits.pdf",
        source_url="http://minio/benefits.pdf",
        request_id=f"req-{document_id}",
    )
    indexing_result = await indexing_service.index(command)
    assert indexing_result.success is True

    answering_service = AnsweringService(
        embedder=embedder,
        vector_store=milvus_store,
        generator=FakeGenerationAdapter(delta_length=4),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in answering_service.answer(knowledge_base_id, "员工福利有哪些？", "gen-pdf")]

    assert isinstance(events[-1], Completed)
    deltas = [event.delta for event in events if isinstance(event, Delta)]
    full_answer = "".join(deltas)
    assert "带薪年假" in full_answer
    assert "健康体检" in full_answer


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_encrypted_pdf_indexing_returns_failure_without_writing_chunks(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> None:
    """加密 PDF 入库失败，不向 Milvus 写入任何片段，回调 FAILED 状态。"""
    content = make_encrypted_pdf("机密内容")
    document_id = f"doc-pdf-enc-{uuid.uuid4().hex[:8]}"
    knowledge_base_id = 90

    callback = InMemoryCallback()
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        document_parser=document_parser,
        chunker=chunker,
        embedder=embedder,
        vector_store=milvus_store,
        callback=callback,
    )
    command = IndexingCommand(
        task_id=f"verify-{document_id}",
        attempt=1,
        knowledge_base_id=knowledge_base_id,
        document_id=document_id,
        filename="secret.pdf",
        source_url="http://minio/secret.pdf",
        request_id=f"req-{document_id}",
    )
    result = await service.index(command)

    assert result.success is False
    assert result.chunks_count == 0
    assert result.diagnostics["error"] == "ValueError"
    assert result.diagnostics["filename"] == "secret.pdf"
    assert await milvus_store.count_by_document(document_id) == 0
    assert callback.results == [result]
