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
