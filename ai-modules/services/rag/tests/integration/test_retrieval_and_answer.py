import logging
import os
import uuid
from collections.abc import Generator

import pytest

from sage_vault_rag.adapters.bge_m3.embedder import BgeM3Embedder
from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.fake_generation.generator import FakeGenerationAdapter
from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.model.events import Completed, Delta, Refused, Started
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
        collection_name=f"verify_retrieval_answer_{uuid.uuid4().hex[:8]}",
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
) -> None:
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(content),
        text_parser=TxtParser(),
        chunker=chunker,
        embedder=embedder,
        vector_store=milvus_store,
        callback=InMemoryCallback(),
    )
    command = IndexingCommand(
        task_id=f"verify-{document_id}",
        attempt=1,
        knowledge_base_id=knowledge_base_id,
        document_id=document_id,
        filename=filename,
        source_url=f"http://minio/{filename}",
        request_id=f"req-{document_id}",
    )
    await service.index(command)


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_answer_streams_based_on_retrieved_chunks(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
) -> None:
    """真实 Milvus 中：索引中文文档后，同一知识库提问应得到基于召回片段的流式回答。"""
    content = " Sage Vault 的员工福利包括带薪年假、健康体检和补充医疗保险。".encode()
    document_id = f"doc-answer-{uuid.uuid4().hex[:8]}"
    knowledge_base_id = 10
    await _index(content, document_id, "benefits.txt", knowledge_base_id, embedder, milvus_store, chunker)

    service = AnsweringService(
        embedder=embedder,
        vector_store=milvus_store,
        generator=FakeGenerationAdapter(delta_length=4),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(knowledge_base_id, "员工福利有哪些？", "gen-1")]

    assert events[0] == Started("gen-1")
    assert isinstance(events[-1], Completed)
    deltas = [event.delta for event in events if isinstance(event, Delta)]
    full_answer = "".join(deltas)
    assert "带薪年假" in full_answer
    assert "健康体检" in full_answer


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_similar_chunks_in_other_knowledge_base_are_not_recalled(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
) -> None:
    """真实 Milvus 隔离：语义相近的其他知识库片段不应被召回。"""
    content_a = " Sage Vault 的员工福利包括带薪年假、健康体检和补充医疗保险。".encode()
    content_b = " 另一家公司也提供带薪年假、健康体检和补充医疗保险。".encode()
    document_a = f"doc-kb-a-{uuid.uuid4().hex[:8]}"
    document_b = f"doc-kb-b-{uuid.uuid4().hex[:8]}"
    await _index(content_a, document_a, "a.txt", 100, embedder, milvus_store, chunker)
    await _index(content_b, document_b, "b.txt", 200, embedder, milvus_store, chunker)

    service = AnsweringService(
        embedder=embedder,
        vector_store=milvus_store,
        generator=FakeGenerationAdapter(delta_length=100),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [event async for event in service.answer(100, "员工福利有哪些？", "gen-2")]

    assert isinstance(events[-1], Completed)
    deltas = [event.delta for event in events if isinstance(event, Delta)]
    full_answer = "".join(deltas)
    assert "Sage Vault" in full_answer
    assert "另一家公司" not in full_answer


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
async def test_unrelated_question_is_refused(
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
) -> None:
    """问题与文档内容无关时应返回 refused。"""
    content = " Sage Vault 的员工福利包括带薪年假。".encode()
    document_id = f"doc-refuse-{uuid.uuid4().hex[:8]}"
    await _index(content, document_id, "benefits.txt", 30, embedder, milvus_store, chunker)

    service = AnsweringService(
        embedder=embedder,
        vector_store=milvus_store,
        generator=FakeGenerationAdapter(delta_length=4),
        top_k=3,
        refusal_threshold=0.1,
    )

    events = [event async for event in service.answer(30, "量子力学的基本原理是什么？", "gen-3")]

    assert events[0] == Started("gen-3")
    assert isinstance(events[-1], Refused)
