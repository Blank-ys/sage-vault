"""MilvusVectorStore 内部 seam 单元测试（无需真实 Milvus）。

覆盖 CollectionLifecycle（schema 迁移、连接恢复、并发初始化）、
RecordCodec（哨兵值双向转换）与 QueryBuilder（表达式转义），
以及 store 对三根 seam 的编排行为。
"""

from __future__ import annotations

import threading
from unittest.mock import MagicMock, patch

import pytest
from pymilvus import MilvusException

from sage_vault_rag.adapters.milvus.collection import CollectionLifecycle
from sage_vault_rag.adapters.milvus.query_builder import QueryBuilder
from sage_vault_rag.adapters.milvus.record_codec import RecordCodec
from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk


@pytest.fixture
def lifecycle() -> CollectionLifecycle:
    return CollectionLifecycle(
        host="127.0.0.1",
        port=19530,
        collection_name="sage_vault_chunks",
        vector_dim=4,
    )


@pytest.fixture
def vector_store() -> MilvusVectorStore:
    return MilvusVectorStore(
        host="127.0.0.1",
        port=19530,
        collection_name="sage_vault_chunks",
        vector_dim=4,
    )


def _make_field_schema(name: str, dtype: int, *, is_primary: bool = False, dim: int | None = None) -> MagicMock:
    """构造模拟的 pymilvus FieldSchema。"""
    field = MagicMock()
    field.name = name
    field.dtype = dtype
    field.is_primary = is_primary
    field.params = {"dim": dim} if dim is not None else {}
    field.dim = dim
    return field


def _make_schema(fields: list[MagicMock]) -> MagicMock:
    """构造模拟的 pymilvus CollectionSchema。"""
    schema = MagicMock()
    schema.fields = fields
    return schema


def _build_expected_fields(vector_dim: int) -> list[MagicMock]:
    """构造与 CollectionLifecycle._build_schema 对应的 9 字段 schema。"""
    return [
        _make_field_schema("chunk_id", 21, is_primary=True),  # VARCHAR
        _make_field_schema("knowledge_base_id", 5),  # INT64
        _make_field_schema("document_id", 21),  # VARCHAR
        _make_field_schema("filename", 21),  # VARCHAR
        _make_field_schema("sequence", 5),  # INT64
        _make_field_schema("text", 21),  # VARCHAR
        _make_field_schema("section_title", 21),  # VARCHAR
        _make_field_schema("page_number", 5),  # INT64
        _make_field_schema("vector", 101, dim=vector_dim),  # FLOAT_VECTOR
    ]


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
def test_get_collection_creates_new_when_missing(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_utility.has_collection.return_value = False

    collection = lifecycle.get_collection()

    mock_utility.has_collection.assert_called_once_with("sage_vault_chunks", using="default")
    mock_utility.drop_collection.assert_not_called()
    mock_collection_class.assert_called_once()
    assert collection is mock_collection_class.return_value


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
def test_get_collection_reuses_existing_when_schema_matches(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_utility.has_collection.return_value = True
    existing_collection = MagicMock()
    existing_collection.schema = _make_schema(_build_expected_fields(4))
    mock_collection_class.return_value = existing_collection

    collection = lifecycle.get_collection()

    mock_utility.has_collection.assert_called_once_with("sage_vault_chunks", using="default")
    mock_utility.drop_collection.assert_not_called()
    assert collection is existing_collection


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
def test_get_collection_drops_and_recreates_when_schema_mismatches(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_utility.has_collection.return_value = True
    existing_collection = MagicMock()
    # 模拟旧版 7 字段 schema，缺少 section_title 与 page_number
    old_fields = [
        _make_field_schema("chunk_id", 21, is_primary=True),
        _make_field_schema("knowledge_base_id", 5),
        _make_field_schema("document_id", 21),
        _make_field_schema("filename", 21),
        _make_field_schema("sequence", 5),
        _make_field_schema("text", 21),
        _make_field_schema("vector", 101, dim=4),
    ]
    existing_collection.schema = _make_schema(old_fields)
    new_collection = MagicMock()
    mock_collection_class.side_effect = [existing_collection, new_collection]

    collection = lifecycle.get_collection()

    mock_utility.has_collection.assert_called_with("sage_vault_chunks", using="default")
    mock_utility.drop_collection.assert_called_once_with("sage_vault_chunks", using="default")
    assert collection is new_collection


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
def test_schema_matches_detects_vector_dim_difference(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_utility.has_collection.return_value = True
    existing_collection = MagicMock()
    wrong_dim_fields = _build_expected_fields(4)
    wrong_dim_fields[-1] = _make_field_schema("vector", 101, dim=99)
    existing_collection.schema = _make_schema(wrong_dim_fields)
    mock_collection_class.side_effect = [existing_collection, MagicMock()]

    lifecycle.get_collection()

    mock_utility.drop_collection.assert_called_once_with("sage_vault_chunks", using="default")


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
def test_schema_matches_detects_dtype_difference(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_utility.has_collection.return_value = True
    existing_collection = MagicMock()
    wrong_dtype_fields = _build_expected_fields(4)
    wrong_dtype_fields[1] = _make_field_schema("knowledge_base_id", 11)  # INT32 而非 INT64
    existing_collection.schema = _make_schema(wrong_dtype_fields)
    mock_collection_class.side_effect = [existing_collection, MagicMock()]

    lifecycle.get_collection()

    mock_utility.drop_collection.assert_called_once_with("sage_vault_chunks", using="default")


@patch("sage_vault_rag.adapters.milvus.collection.connections")
def test_connect_recovers_when_connection_already_exists(
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_connections.connect.side_effect = MilvusException("timeout")
    mock_connections.has_connection.return_value = True

    lifecycle.connect()

    mock_connections.has_connection.assert_called_once_with("default")


@patch("sage_vault_rag.adapters.milvus.collection.connections")
def test_connect_raises_when_no_connection_exists(
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_connections.connect.side_effect = MilvusException("timeout")
    mock_connections.has_connection.return_value = False

    with pytest.raises(MilvusException):
        lifecycle.connect()


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
def test_concurrent_initialization_creates_collection_once(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    lifecycle: CollectionLifecycle,
) -> None:
    mock_utility.has_collection.return_value = False

    def access() -> object:
        return lifecycle.get_collection()

    threads = [threading.Thread(target=access) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    mock_collection_class.assert_called_once()


def test_encode_converts_none_metadata_to_sentinels() -> None:
    chunks = [
        Chunk(
            chunk_id="c1",
            knowledge_base_id=1,
            document_id="d1",
            filename="regulations.md",
            sequence=0,
            text="知识库管理办法总则",
            section_title="总则",
            page_number=3,
        ),
        Chunk(
            chunk_id="c2",
            knowledge_base_id=1,
            document_id="d1",
            filename="plain.txt",
            sequence=1,
            text="普通段落",
        ),
    ]
    vectors = [[0.1, 0.2, 0.3, 0.4], [0.2, 0.3, 0.4, 0.5]]

    entities = RecordCodec().encode(chunks, vectors)

    assert entities[0] == ["c1", "c2"]
    assert entities[1] == [1, 1]
    assert entities[6] == ["总则", ""]
    assert entities[7] == [3, 0]
    assert entities[8] == vectors


def test_decode_converts_sentinels_back_to_none() -> None:
    hit_with_meta = MagicMock()
    hit_with_meta.entity = {
        "chunk_id": "c1",
        "document_id": "d1",
        "filename": "regulations.md",
        "sequence": 0,
        "text": "知识库管理办法总则",
        "section_title": "总则",
        "page_number": 3,
    }
    hit_with_meta.distance = 0.3
    hit_without_meta = MagicMock()
    hit_without_meta.entity = {
        "chunk_id": "c2",
        "document_id": "d1",
        "filename": "plain.txt",
        "sequence": 1,
        "text": "普通段落",
        "section_title": "",
        "page_number": 0,
    }
    hit_without_meta.distance = 0.5

    decoded = RecordCodec().decode([hit_with_meta, hit_without_meta])

    assert decoded == [
        RetrievedChunk("c1", "d1", "regulations.md", 0, "知识库管理办法总则", score=0.3, section_title="总则", page_number=3),
        RetrievedChunk("c2", "d1", "plain.txt", 1, "普通段落", score=0.5),
    ]


def test_document_expr_escapes_quotes_and_backslashes() -> None:
    assert QueryBuilder.document_expr("plain-id") == 'document_id == "plain-id"'
    assert QueryBuilder.document_expr('a"b\\c') == 'document_id == "a\\"b\\\\c"'


def test_knowledge_base_filter() -> None:
    assert QueryBuilder.knowledge_base_filter(42) == "knowledge_base_id == 42"


@pytest.mark.asyncio
async def test_save_chunks_rejects_length_mismatch(vector_store: MilvusVectorStore) -> None:
    with pytest.raises(ValueError):
        await vector_store.save_chunks(
            [
                Chunk(
                    chunk_id="c1",
                    knowledge_base_id=1,
                    document_id="d1",
                    filename="f.txt",
                    sequence=0,
                    text="片段",
                )
            ],
            [[0.1, 0.2, 0.3, 0.4], [0.2, 0.3, 0.4, 0.5]],
        )


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
@pytest.mark.asyncio
async def test_save_chunks_inserts_entities_and_flushes(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = False
    collection = mock_collection_class.return_value
    chunks = [
        Chunk(
            chunk_id="c1",
            knowledge_base_id=1,
            document_id="d1",
            filename="f.txt",
            sequence=0,
            text="片段",
        )
    ]
    vectors = [[0.1, 0.2, 0.3, 0.4]]

    await vector_store.save_chunks(chunks, vectors)

    collection.insert.assert_called_once()
    collection.flush.assert_called_once()


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
@pytest.mark.asyncio
async def test_delete_by_document_loads_deletes_and_flushes(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = False
    collection = mock_collection_class.return_value

    await vector_store.delete_by_document("doc-1")

    collection.load.assert_called_once()
    collection.delete.assert_called_once()
    _, delete_kwargs = collection.delete.call_args
    assert delete_kwargs["expr"] == 'document_id == "doc-1"'
    collection.flush.assert_called_once()


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
@pytest.mark.asyncio
async def test_search_filters_by_knowledge_base_and_decodes(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = False
    collection = mock_collection_class.return_value
    hit = MagicMock()
    hit.entity = {
        "chunk_id": "c1",
        "document_id": "d1",
        "filename": "f.txt",
        "sequence": 0,
        "text": "答案",
        "section_title": "",
        "page_number": 0,
    }
    hit.distance = 0.3
    collection.search.return_value = [[hit]]

    results = await vector_store.search(42, [0.1, 0.2, 0.3, 0.4], 3)

    _, search_kwargs = collection.search.call_args
    assert search_kwargs["expr"] == "knowledge_base_id == 42"
    assert search_kwargs["limit"] == 3
    assert results == [RetrievedChunk("c1", "d1", "f.txt", 0, "答案", score=0.3)]


@patch("sage_vault_rag.adapters.milvus.collection.connections")
@patch("sage_vault_rag.adapters.milvus.collection.utility")
@patch("sage_vault_rag.adapters.milvus.collection.Collection")
@pytest.mark.asyncio
async def test_count_by_document_paginates(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = False
    collection = mock_collection_class.return_value
    full_page = [{"chunk_id": f"c{i}"} for i in range(16384)]
    tail = [{"chunk_id": "c16384"}]
    collection.query.side_effect = [full_page, tail]

    total = await vector_store.count_by_document("doc-1")

    assert total == 16385
    assert collection.query.call_count == 2
