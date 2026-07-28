import os
import uuid

import pytest
from pymilvus import MilvusException

from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.model.chunk import Chunk


@pytest.fixture
def milvus_available() -> bool:
    host = os.environ.get("SAGE_VAULT_RAG_MILVUS_HOST", "127.0.0.1")
    port = int(os.environ.get("SAGE_VAULT_RAG_MILVUS_PORT", "19530"))
    store = MilvusVectorStore(
        host=host,
        port=port,
        collection_name="test_sage_vault_chunks",
        vector_dim=4,
    )
    try:
        store._connect()
        return True
    except (OSError, MilvusException):
        return False


@pytest.fixture
def vector_store() -> MilvusVectorStore:
    host = os.environ.get("SAGE_VAULT_RAG_MILVUS_HOST", "127.0.0.1")
    port = int(os.environ.get("SAGE_VAULT_RAG_MILVUS_PORT", "19530"))
    return MilvusVectorStore(
        host=host,
        port=port,
        collection_name=f"test_sage_vault_chunks_{uuid.uuid4().hex[:8]}",
        vector_dim=4,
    )


def _chunks(document_id: str, knowledge_base_id: int, count: int) -> list[Chunk]:
    return [
        Chunk(
            chunk_id=f"{document_id}-{index}",
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
            filename="test.txt",
            sequence=index,
            text=f"片段 {index}",
        )
        for index in range(count)
    ]


def _chunks_with_metadata(document_id: str, knowledge_base_id: int) -> list[Chunk]:
    """构造带 section_title/page_number 的片段，用于验证元数据 round-trip。"""
    return [
        Chunk(
            chunk_id=f"{document_id}-title",
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
            filename="regulations.md",
            sequence=0,
            text="知识库管理办法总则",
            section_title="总则",
            page_number=None,
        ),
        Chunk(
            chunk_id=f"{document_id}-page",
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
            filename="regulations.pdf",
            sequence=1,
            text="第三页的正文内容",
            section_title=None,
            page_number=3,
        ),
        Chunk(
            chunk_id=f"{document_id}-both",
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
            filename="regulations.docx",
            sequence=2,
            text="第二章适用范围",
            section_title="适用范围",
            page_number=5,
        ),
    ]


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_save_and_delete_by_document(vector_store: MilvusVectorStore, milvus_available: bool) -> None:
    if not milvus_available:
        pytest.skip("Milvus 不可达")
    chunks = _chunks("doc-a", 1, 3)
    vectors = [[0.1, 0.2, 0.3, 0.4], [0.2, 0.3, 0.4, 0.5], [0.3, 0.4, 0.5, 0.6]]

    await vector_store.save_chunks(chunks, vectors)
    assert await vector_store.count_by_document("doc-a") == 3

    await vector_store.delete_by_document("doc-a")
    assert await vector_store.count_by_document("doc-a") == 0


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_documents_are_isolated_by_document_id(vector_store: MilvusVectorStore, milvus_available: bool) -> None:
    if not milvus_available:
        pytest.skip("Milvus 不可达")
    chunks_a = _chunks("doc-a", 1, 2)
    chunks_b = _chunks("doc-b", 2, 2)
    vectors = [[0.1, 0.2, 0.3, 0.4], [0.2, 0.3, 0.4, 0.5]]

    await vector_store.save_chunks(chunks_a, vectors)
    await vector_store.save_chunks(chunks_b, vectors)

    assert await vector_store.count_by_document("doc-a") == 2
    assert await vector_store.count_by_document("doc-b") == 2

    await vector_store.delete_by_document("doc-a")
    assert await vector_store.count_by_document("doc-a") == 0
    assert await vector_store.count_by_document("doc-b") == 2


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_search_returns_section_title_and_page_number(vector_store: MilvusVectorStore, milvus_available: bool) -> None:
    """写入带元数据的片段后，search 应在召回结果中回显 section_title 与 page_number。"""
    if not milvus_available:
        pytest.skip("Milvus 不可达")
    chunks = _chunks_with_metadata("doc-meta", 42)
    vectors = [
        [0.9, 0.1, 0.0, 0.0],
        [0.1, 0.9, 0.0, 0.0],
        [0.5, 0.5, 0.0, 0.0],
    ]

    await vector_store.save_chunks(chunks, vectors)

    results = await vector_store.search(knowledge_base_id=42, vector=[0.9, 0.1, 0.0, 0.0], top_k=3)
    assert len(results) == 3
    by_chunk_id = {chunk.chunk_id: chunk for chunk in chunks}
    for result in results:
        original = by_chunk_id[result.chunk_id]
        assert result.section_title == original.section_title
        assert result.page_number == original.page_number
        assert result.filename == original.filename
        assert result.sequence == original.sequence


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_search_returns_none_metadata_for_chunks_without_section_title_or_page(
    vector_store: MilvusVectorStore, milvus_available: bool
) -> None:
    """TXT 等无元数据片段入库后，search 召回时 section_title/page_number 应为 None。"""
    if not milvus_available:
        pytest.skip("Milvus 不可达")
    chunks = _chunks("doc-plain", 7, 2)
    vectors = [[0.1, 0.2, 0.3, 0.4], [0.2, 0.3, 0.4, 0.5]]

    await vector_store.save_chunks(chunks, vectors)

    results = await vector_store.search(knowledge_base_id=7, vector=[0.1, 0.2, 0.3, 0.4], top_k=2)
    assert len(results) == 2
    for result in results:
        assert result.section_title is None
        assert result.page_number is None
