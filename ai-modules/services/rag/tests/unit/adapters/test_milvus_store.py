"""MilvusVectorStore 单元测试（无需真实 Milvus）。"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from sage_vault_rag.adapters.milvus.store import MilvusVectorStore


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
    """构造与 MilvusVectorStore._build_schema 对应的 9 字段 schema。"""
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


@patch("sage_vault_rag.adapters.milvus.store.connections")
@patch("sage_vault_rag.adapters.milvus.store.utility")
@patch("sage_vault_rag.adapters.milvus.store.Collection")
def test_get_collection_creates_new_when_missing(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = False

    collection = vector_store._get_collection()

    mock_utility.has_collection.assert_called_once_with("sage_vault_chunks", using="default")
    mock_utility.drop_collection.assert_not_called()
    mock_collection_class.assert_called_once()
    assert collection is mock_collection_class.return_value


@patch("sage_vault_rag.adapters.milvus.store.connections")
@patch("sage_vault_rag.adapters.milvus.store.utility")
@patch("sage_vault_rag.adapters.milvus.store.Collection")
def test_get_collection_reuses_existing_when_schema_matches(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = True
    existing_collection = MagicMock()
    existing_collection.schema = _make_schema(_build_expected_fields(4))
    mock_collection_class.return_value = existing_collection

    collection = vector_store._get_collection()

    mock_utility.has_collection.assert_called_once_with("sage_vault_chunks", using="default")
    mock_utility.drop_collection.assert_not_called()
    assert collection is existing_collection


@patch("sage_vault_rag.adapters.milvus.store.connections")
@patch("sage_vault_rag.adapters.milvus.store.utility")
@patch("sage_vault_rag.adapters.milvus.store.Collection")
def test_get_collection_drops_and_recreates_when_schema_mismatches(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
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

    collection = vector_store._get_collection()

    mock_utility.has_collection.assert_called_with("sage_vault_chunks", using="default")
    mock_utility.drop_collection.assert_called_once_with("sage_vault_chunks", using="default")
    assert collection is new_collection


@patch("sage_vault_rag.adapters.milvus.store.connections")
@patch("sage_vault_rag.adapters.milvus.store.utility")
@patch("sage_vault_rag.adapters.milvus.store.Collection")
def test_schema_matches_detects_vector_dim_difference(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = True
    existing_collection = MagicMock()
    wrong_dim_fields = _build_expected_fields(4)
    wrong_dim_fields[-1] = _make_field_schema("vector", 101, dim=99)
    existing_collection.schema = _make_schema(wrong_dim_fields)
    mock_collection_class.side_effect = [existing_collection, MagicMock()]

    vector_store._get_collection()

    mock_utility.drop_collection.assert_called_once_with("sage_vault_chunks", using="default")


@patch("sage_vault_rag.adapters.milvus.store.connections")
@patch("sage_vault_rag.adapters.milvus.store.utility")
@patch("sage_vault_rag.adapters.milvus.store.Collection")
def test_schema_matches_detects_dtype_difference(
    mock_collection_class: MagicMock,
    mock_utility: MagicMock,
    mock_connections: MagicMock,
    vector_store: MilvusVectorStore,
) -> None:
    mock_utility.has_collection.return_value = True
    existing_collection = MagicMock()
    wrong_dtype_fields = _build_expected_fields(4)
    wrong_dtype_fields[1] = _make_field_schema("knowledge_base_id", 11)  # INT32 而非 INT64
    existing_collection.schema = _make_schema(wrong_dtype_fields)
    mock_collection_class.side_effect = [existing_collection, MagicMock()]

    vector_store._get_collection()

    mock_utility.drop_collection.assert_called_once_with("sage_vault_chunks", using="default")
