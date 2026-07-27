import logging
import os
import uuid

import pytest

from sage_vault_rag.adapters.bge_m3.embedder import BgeM3Embedder
from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.indexing_result import IndexingResult
from sage_vault_rag.ports.callback import CallbackPort
from sage_vault_rag.ports.document_storage import DocumentStoragePort


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
    model_path = os.environ.get(
        "SAGE_VAULT_RAG_EMBEDDING_MODEL_PATH",
        r"F:\tmp\models\BAAI--bge-m3\snapshots\master",
    )
    return BgeM3Embedder(
        model_path=model_path,
        profile="cpu-dev",
        batch_size=2,
    )


@pytest.fixture
def milvus_store():
    host = os.environ.get("SAGE_VAULT_RAG_MILVUS_HOST", "127.0.0.1")
    port = int(os.environ.get("SAGE_VAULT_RAG_MILVUS_PORT", "19530"))
    store = MilvusVectorStore(
        host=host,
        port=port,
        collection_name=f"verify_txt_indexing_{uuid.uuid4().hex[:8]}",
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


async def _index(
    content: bytes,
    document_id: str,
    filename: str,
    knowledge_base_id: int,
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
) -> tuple[IndexingCommand, IndexingResult]:
    callback = InMemoryCallback()
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        text_parser=TxtParser(),
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
        filename=filename,
        source_url=f"http://minio/{filename}",
    )
    result = await service.index(command)
    return command, result


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_short_single_paragraph_document(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
) -> None:
    """单一段落短文档应生成 1 个 chunk 并入 Milvus。"""
    content = "Sage Vault 是企业知识库问答系统，支持文档上传与智能检索。".encode()
    document_id = f"doc-short-{uuid.uuid4().hex[:8]}"
    command, result = await _index(
        content, document_id, "short.txt", 1, embedder, milvus_store, chunker
    )

    assert result.success is True
    assert result.chunks_count == 1
    assert await milvus_store.count_by_document(command.document_id) == 1

    collection = milvus_store._get_collection()
    collection.load()
    rows = collection.query(
        expr=milvus_store._document_expr(command.document_id),
        output_fields=["chunk_id", "knowledge_base_id", "document_id", "filename", "sequence", "text"],
    )
    assert len(rows) == 1
    assert rows[0]["knowledge_base_id"] == 1
    assert rows[0]["document_id"] == command.document_id
    assert rows[0]["filename"] == "short.txt"
    assert rows[0]["sequence"] == 0
    assert "Sage Vault" in rows[0]["text"]


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_multi_paragraph_document_keeps_boundaries(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
) -> None:
    """多段落文档应保持自然段落边界，每个段落对应一个 chunk。"""
    content = (
        "第一章 总则\n\n"
        "第一条 为规范公司知识管理，制定本办法。\n\n"
        "第二条 本办法适用于全体员工。\n\n"
        "第三条 知识库按照主题进行分类维护。"
    ).encode()
    document_id = f"doc-paragraphs-{uuid.uuid4().hex[:8]}"
    command, result = await _index(
        content, document_id, "paragraphs.txt", 2, embedder, milvus_store, chunker
    )

    assert result.success is True
    assert result.chunks_count == 2
    assert await milvus_store.count_by_document(command.document_id) == 2

    collection = milvus_store._get_collection()
    collection.load()
    rows = collection.query(
        expr=milvus_store._document_expr(command.document_id),
        output_fields=["sequence", "text"],
    )
    rows.sort(key=lambda row: row["sequence"])
    assert len(rows) == 2
    assert rows[0]["text"].startswith("第一章 总则")
    assert "本办法适用于全体员工" in rows[0]["text"]
    assert rows[1]["text"].startswith("第三条")


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_long_paragraph_is_chunked(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
) -> None:
    """超长段落应按 chunk_size 切分为多个 chunk。"""
    paragraph = "人工智能正在改变企业知识管理。" * 20
    content = paragraph.encode()
    document_id = f"doc-long-{uuid.uuid4().hex[:8]}"
    command, result = await _index(
        content, document_id, "long.txt", 3, embedder, milvus_store, chunker
    )

    assert result.success is True
    assert result.chunks_count > 1
    assert await milvus_store.count_by_document(command.document_id) == result.chunks_count

    collection = milvus_store._get_collection()
    collection.load()
    rows = collection.query(
        expr=milvus_store._document_expr(command.document_id),
        output_fields=["text"],
    )
    full_text = "".join(row["text"] for row in rows)
    assert "人工智能正在改变企业知识管理" in full_text
