"""03f — 解析器集成测试与中文验收。

覆盖四种成功格式（TXT/PDF/DOCX/MD）的完整入库链路，断言可观察的中文文本和来源元数据；
覆盖代表性失败夹具（加密/损坏/空白/扫描版 PDF、损坏/空白 DOCX、空 MD）的端到端失败路径；
并用中文问答验证各格式文档内容可被召回并回答。

不绑定具体解析库内部实现，只验证 ``ParsedDocument`` 契约与 ``IndexingService`` 行为。
所有用例通过生产装配的 ``FormatDispatchingDocumentParser``（四种解析器全部注册）执行，
确保 03a-03e 的解析器、元数据扩展与切块器在统一分发路径下协同工作。

注：为验证 chunk 顺序与 Milvus 行级元数据，部分测试直接读取 ``MilvusVectorStore`` 的
collection 对象（与 03b-03e 既有端到端测试一致）；这部分属于存储适配器边界内的可观察
行为，不绑定 pymilvus 内部 API。
"""

import logging
import os
import uuid
from collections.abc import Generator
from dataclasses import dataclass

import pytest

from sage_vault_rag.adapters.bge_m3.embedder import BgeM3Embedder
from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.document_parser.dispatcher import FormatDispatchingDocumentParser
from sage_vault_rag.adapters.docx_parser.parser import DocxParser
from sage_vault_rag.adapters.fake_generation.generator import FakeGenerationAdapter
from sage_vault_rag.adapters.markdown_parser.parser import MarkdownParser
from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.adapters.pdf_parser.parser import PdfParser
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.model.events import Completed, Delta
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.indexing_result import IndexingResult
from sage_vault_rag.ports.callback import CallbackPort
from sage_vault_rag.ports.document_storage import DocumentStoragePort
from tests._docx_fixtures import make_docx_with_headings, make_empty_docx
from tests._pdf_fixtures import make_blank_pdf, make_encrypted_pdf, make_text_pdf


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
        collection_name=f"verify_03f_acceptance_{uuid.uuid4().hex[:8]}",
        vector_dim=1024,
    )
    yield store
    try:
        store._lifecycle.get_collection()
        from pymilvus import utility

        utility.drop_collection(store._lifecycle.collection_name, using=store._lifecycle.alias)
    except Exception:
        logging.getLogger(__name__).debug("清理测试 collection 失败", exc_info=True)


@pytest.fixture
def chunker() -> ParagraphChunker:
    return ParagraphChunker(chunk_size=64, chunk_overlap=8)


@pytest.fixture
def document_parser() -> FormatDispatchingDocumentParser:
    """装配生产路径使用的全格式分发解析器（TXT/MD/PDF/DOCX 全部注册）。"""
    return FormatDispatchingDocumentParser(
        {
            "txt": TxtParser(),
            "md": MarkdownParser(),
            "pdf": PdfParser(),
            "docx": DocxParser(),
        }
    )


@dataclass(frozen=True)
class SuccessFixture:
    """成功入库夹具：携带内容、预期中文召回文本与来源元数据。"""

    name: str
    filename: str
    content: bytes
    knowledge_base_id: int
    expected_text: str
    expected_section_title: str | None
    expected_page_number: int | None
    search_query: str


# 四种成功格式夹具；knowledge_base_id 取 200~231 段，避免与既有测试集合冲突
SUCCESS_FIXTURES: list[SuccessFixture] = [
    SuccessFixture(
        name="txt",
        filename="regulations.txt",
        content=(
            "第一章 总则\n\n"
            "第一条 为规范公司知识管理，制定本办法。\n\n"
            "第二条 本办法适用于全体员工。"
        ).encode(),
        knowledge_base_id=200,
        expected_text="本办法适用于全体员工",
        expected_section_title=None,
        expected_page_number=None,
        search_query="本办法适用范围",
    ),
    SuccessFixture(
        name="md",
        filename="regulations.md",
        content=(
            "# 知识库管理办法\n\n"
            "本办法用于规范公司知识管理。\n\n"
            "## 知识库分类\n\n"
            "知识库按照主题进行分类维护，包含技术、产品与运营三类。"
        ).encode(),
        knowledge_base_id=210,
        expected_text="知识库按照主题进行分类维护",
        expected_section_title="知识库管理办法",
        expected_page_number=None,
        search_query="知识库如何分类",
    ),
    SuccessFixture(
        name="pdf",
        filename="regulations.pdf",
        content=make_text_pdf("知识库管理办法总则适用于全体员工"),
        knowledge_base_id=220,
        expected_text="知识库管理办法",
        expected_section_title=None,
        expected_page_number=1,
        search_query="知识库管理办法",
    ),
    SuccessFixture(
        name="docx",
        filename="regulations.docx",
        content=make_docx_with_headings(
            [
                (1, "知识库管理办法", ["本办法用于规范公司知识管理。"]),
                (2, "适用范围", ["本办法适用于全体正式员工。"]),
            ]
        ),
        knowledge_base_id=230,
        expected_text="本办法适用于全体正式员工",
        # chunker 在 chunk_size 内合并多段时取首个非空 heading 作为 section_title，
        # 本夹具所有段落合并为单个 chunk，标题为一级标题"知识库管理办法"
        expected_section_title="知识库管理办法",
        expected_page_number=None,
        search_query="本办法适用范围",
    ),
]


async def _index_fixture(
    fixture: SuccessFixture,
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> tuple[str, IndexingResult]:
    """对成功夹具执行完整入库，返回 (document_id, result)；不断言 success，由调用方断言。"""
    document_id = f"doc-03f-{fixture.name}-{uuid.uuid4().hex[:8]}"
    callback = InMemoryCallback()
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(fixture.content),
        document_parser=document_parser,
        chunker=chunker,
        embedder=embedder,
        vector_store=milvus_store,
        callback=callback,
    )
    command = IndexingCommand(
        task_id=f"verify-{document_id}",
        attempt=1,
        knowledge_base_id=fixture.knowledge_base_id,
        document_id=document_id,
        filename=fixture.filename,
        source_url=f"http://minio/{fixture.filename}",
        request_id=f"req-{document_id}",
    )
    result = await service.index(command)
    return document_id, result


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
@pytest.mark.parametrize("fixture", SUCCESS_FIXTURES, ids=[f.name for f in SUCCESS_FIXTURES])
async def test_four_formats_indexing_succeeds_and_publishes_chinese_chunks(
    fixture: SuccessFixture,
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> None:
    """四种格式成功入库后，Milvus 中应能观察到中文文本与来源元数据（文件名、片段顺序、页码/标题）。"""
    document_id, result = await _index_fixture(fixture, embedder, milvus_store, chunker, document_parser)

    assert result.success is True
    assert result.chunks_count >= 1
    assert await milvus_store.count_by_document(document_id) == result.chunks_count

    collection = milvus_store._lifecycle.get_collection()
    collection.load()
    rows = collection.query(
        expr=milvus_store._queries.document_expr(document_id),
        output_fields=["filename", "sequence", "text", "section_title", "page_number"],
    )
    rows.sort(key=lambda row: row["sequence"])
    assert len(rows) == result.chunks_count
    # 来源元数据：文件名一致、片段顺序从 0 开始连续
    for index, row in enumerate(rows):
        assert row["filename"] == fixture.filename
        assert row["sequence"] == index
    # 页码元数据：PDF 页码为 1，其余格式无页码
    if fixture.expected_page_number is not None:
        for row in rows:
            assert row["page_number"] == fixture.expected_page_number
    else:
        for row in rows:
            assert row["page_number"] == 0  # Milvus 哨兵值 0 表示 None
    # 标题元数据：TXT/PDF 无标题；MD/DOCX 至少一个片段携带预期标题
    if fixture.expected_section_title is None:
        for row in rows:
            assert row["section_title"] == ""  # Milvus 哨兵值 "" 表示 None
    else:
        row_titles = {row["section_title"] for row in rows if row["section_title"] != ""}
        assert fixture.expected_section_title in row_titles
    # 召回文本包含预期中文内容
    full_text = "\n\n".join(row["text"] for row in rows)
    assert fixture.expected_text in full_text


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
@pytest.mark.parametrize("fixture", SUCCESS_FIXTURES, ids=[f.name for f in SUCCESS_FIXTURES])
async def test_four_formats_search_returns_source_metadata(
    fixture: SuccessFixture,
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> None:
    """四种格式入库后，search 召回片段应携带正确的来源元数据（页码/标题）。"""
    document_id, _ = await _index_fixture(fixture, embedder, milvus_store, chunker, document_parser)

    query_vector = (await embedder.embed([fixture.search_query]))[0]
    retrieved = await milvus_store.search(fixture.knowledge_base_id, query_vector, top_k=10)
    assert len(retrieved) >= 1
    # 召回片段都属于本次入库的文档，文件名一致
    for chunk in retrieved:
        assert chunk.document_id == document_id
        assert chunk.filename == fixture.filename
    # 页码元数据按格式校验：PDF 页码为 1，其余格式无页码
    if fixture.expected_page_number is not None:
        for chunk in retrieved:
            assert chunk.page_number == fixture.expected_page_number
    else:
        for chunk in retrieved:
            assert chunk.page_number is None
    # 标题元数据按格式校验：
    # - TXT/PDF：所有片段 section_title 为 None
    # - MD/DOCX：至少一个片段 section_title 等于预期标题（多标题文档不同片段标题可能不同）
    if fixture.expected_section_title is None:
        for chunk in retrieved:
            assert chunk.section_title is None
    else:
        retrieved_titles = {chunk.section_title for chunk in retrieved if chunk.section_title is not None}
        assert fixture.expected_section_title in retrieved_titles


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
@pytest.mark.parametrize("fixture", SUCCESS_FIXTURES, ids=[f.name for f in SUCCESS_FIXTURES])
async def test_four_formats_chinese_qa_recalls_and_answers(
    fixture: SuccessFixture,
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> None:
    """四种格式文档内容可被中文问答召回并流式回答。"""
    _, result = await _index_fixture(fixture, embedder, milvus_store, chunker, document_parser)
    assert result.success is True

    answering_service = AnsweringService(
        embedder=embedder,
        vector_store=milvus_store,
        generator=FakeGenerationAdapter(delta_length=4),
        top_k=3,
        refusal_threshold=1.0,
    )

    events = [
        event
        async for event in answering_service.answer(
            fixture.knowledge_base_id,
            fixture.search_query,
            f"gen-03f-{fixture.name}",
        )
    ]

    assert isinstance(events[-1], Completed)
    deltas = [event.delta for event in events if isinstance(event, Delta)]
    full_answer = "".join(deltas)
    assert fixture.expected_text in full_answer


@dataclass(frozen=True)
class FailureFixture:
    """失败入库夹具：携带内容与预期失败行为。"""

    name: str
    filename: str
    content: bytes
    knowledge_base_id: int
    description: str


# 代表性失败夹具；knowledge_base_id 取 290~296 段
FAILURE_FIXTURES: list[FailureFixture] = [
    FailureFixture(
        name="encrypted_pdf",
        filename="secret.pdf",
        content=make_encrypted_pdf("机密内容"),
        knowledge_base_id=290,
        description="加密 PDF",
    ),
    FailureFixture(
        name="corrupted_pdf",
        filename="broken.pdf",
        content=b"This is not a valid PDF file content",
        knowledge_base_id=291,
        description="损坏 PDF",
    ),
    FailureFixture(
        name="blank_pdf",
        filename="blank.pdf",
        content=b"",
        knowledge_base_id=292,
        description="空白 PDF（空字节）",
    ),
    FailureFixture(
        name="scanned_pdf",
        filename="scanned.pdf",
        content=make_blank_pdf(),
        knowledge_base_id=293,
        description="扫描版 PDF（有效 PDF 但无文本层）",
    ),
    FailureFixture(
        name="corrupted_docx",
        filename="broken.docx",
        content=b"This is not a valid DOCX file content",
        knowledge_base_id=294,
        description="损坏 DOCX",
    ),
    FailureFixture(
        name="blank_docx",
        filename="empty.docx",
        content=make_empty_docx(),
        knowledge_base_id=295,
        description="空白 DOCX",
    ),
    FailureFixture(
        name="empty_md",
        filename="empty.md",
        content=b"",
        knowledge_base_id=296,
        description="空 MD",
    ),
]


@pytest.mark.asyncio
@pytest.mark.skipif(not os.environ.get("SAGE_VAULT_RAG_RUN_MILVUS_TESTS"), reason="需要显式启用 Milvus 集成测试")
@pytest.mark.parametrize("fixture", FAILURE_FIXTURES, ids=[f.name for f in FAILURE_FIXTURES])
async def test_failure_fixtures_return_failed_without_writing_chunks(
    fixture: FailureFixture,
    embedder: BgeM3Embedder,
    milvus_store: MilvusVectorStore,
    chunker: ParagraphChunker,
    document_parser: FormatDispatchingDocumentParser,
) -> None:
    """代表性失败夹具入库失败，不向 Milvus 写入任何片段，回调 FAILED 状态。

    所有失败均由解析器抛 ``ValueError``，经 ``IndexingService`` 统一走清理与回调路径，
    ``diagnostics`` 仅记录异常类型与文件名，不向 Java 暴露异常消息。
    """
    document_id = f"doc-03f-fail-{fixture.name}-{uuid.uuid4().hex[:8]}"
    callback = InMemoryCallback()
    service = IndexingService(
        document_storage=InMemoryDocumentStorage(fixture.content),
        document_parser=document_parser,
        chunker=chunker,
        embedder=embedder,
        vector_store=milvus_store,
        callback=callback,
    )
    command = IndexingCommand(
        task_id=f"verify-{document_id}",
        attempt=1,
        knowledge_base_id=fixture.knowledge_base_id,
        document_id=document_id,
        filename=fixture.filename,
        source_url=f"http://minio/{fixture.filename}",
        request_id=f"req-{document_id}",
    )
    result = await service.index(command)

    assert result.success is False
    assert result.chunks_count == 0
    assert result.diagnostics["error"] == "ValueError"
    assert result.diagnostics["filename"] == fixture.filename
    assert await milvus_store.count_by_document(document_id) == 0
    assert callback.results == [result]
