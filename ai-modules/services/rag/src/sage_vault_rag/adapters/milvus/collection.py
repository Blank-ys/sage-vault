"""Collection 生命周期 seam：连接恢复、schema 迁移、索引与缓存。

Milvus adapter 内部变化轴较多；本模块只收拢 collection 的建立与维护，
实体编解码见 record_codec，查询表达式见 query_builder。
"""

import logging
import threading

from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    MilvusException,
    connections,
    utility,
)

logger = logging.getLogger(__name__)


class CollectionLifecycle:
    """连接 Milvus 并维护目标 collection 的存在、schema 与索引。

    首次访问时惰性初始化；schema 不匹配的旧 collection 会被 drop 重建。
    并发首次初始化由锁保护，避免重复创建或重复 drop collection。
    """

    def __init__(
        self,
        host: str,
        port: int,
        collection_name: str,
        vector_dim: int,
        alias: str = "default",
    ) -> None:
        self.collection_name = collection_name
        self.alias = alias
        self._host = host
        self._port = port
        self._vector_dim = vector_dim
        self._collection: Collection | None = None
        self._init_lock = threading.Lock()

    def connect(self) -> None:
        """建立连接；已存在可用连接时忽略 connect 失败以支持连接恢复。"""
        try:
            connections.connect(alias=self.alias, host=self._host, port=self._port)
        except MilvusException:
            if not connections.has_connection(self.alias):
                raise

    def get_collection(self) -> Collection:
        """返回已就绪的 collection；必要时创建或按 schema 重建。"""
        with self._init_lock:
            if self._collection is not None:
                return self._collection
            self.connect()
            if utility.has_collection(self.collection_name, using=self.alias):
                existing = Collection(self.collection_name, using=self.alias)
                if self._schema_matches(existing):
                    self._collection = existing
                    return self._collection
                logger.warning(
                    "Collection %s schema mismatch; dropping and recreating. "
                    "Expected fields: %s, existing fields: %s",
                    self.collection_name,
                    [field.name for field in self._build_schema().fields],
                    [field.name for field in existing.schema.fields],
                )
                utility.drop_collection(self.collection_name, using=self.alias)
            schema = self._build_schema()
            self._collection = Collection(
                name=self.collection_name,
                schema=schema,
                using=self.alias,
            )
            self._ensure_index()
            return self._collection

    def _ensure_index(self) -> None:
        if self._collection is None:
            return
        if self._collection.indexes:
            return
        index_params = {
            "index_type": "FLAT",
            "metric_type": "L2",
            "params": {},
        }
        self._collection.create_index(field_name="vector", index_params=index_params)

    def _build_schema(self) -> CollectionSchema:
        # pymilvus 2.4.x 的 FieldSchema 静默忽略 nullable=True，VARCHAR/INT64 字段
        # 不支持 None 值入库。section_title/page_number 使用空字符串/0 作为哨兵值
        # 表示"无元数据"，由 record codec 在适配器边界与 None 双向转换。
        fields = [
            FieldSchema(name="chunk_id", dtype=DataType.VARCHAR, max_length=64, is_primary=True),
            FieldSchema(name="knowledge_base_id", dtype=DataType.INT64),
            FieldSchema(name="document_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="filename", dtype=DataType.VARCHAR, max_length=512),
            FieldSchema(name="sequence", dtype=DataType.INT64),
            FieldSchema(name="text", dtype=DataType.VARCHAR, max_length=65535),
            FieldSchema(name="section_title", dtype=DataType.VARCHAR, max_length=512),
            FieldSchema(name="page_number", dtype=DataType.INT64),
            FieldSchema(name="vector", dtype=DataType.FLOAT_VECTOR, dim=self._vector_dim),
        ]
        return CollectionSchema(fields, description="Sage Vault document chunks")

    def _schema_matches(self, collection: Collection) -> bool:
        """校验现有 collection 的 schema 与目标 schema 是否一致。"""
        expected = self._build_schema()
        expected_fields = {field.name: field for field in expected.fields}
        actual_fields = {field.name: field for field in collection.schema.fields}
        if set(expected_fields.keys()) != set(actual_fields.keys()):
            return False
        for name, expected_field in expected_fields.items():
            actual_field = actual_fields[name]
            if expected_field.dtype != actual_field.dtype:
                return False
            if expected_field.is_primary != actual_field.is_primary:
                return False
            if expected_field.dtype == DataType.FLOAT_VECTOR:
                expected_dim = self._vector_dim_from_field(expected_field)
                actual_dim = self._vector_dim_from_field(actual_field)
                if expected_dim != actual_dim:
                    return False
        return True

    @staticmethod
    def _vector_dim_from_field(field: FieldSchema) -> int | None:
        """从 FieldSchema 中读取向量维度。"""
        dim = getattr(field, "dim", None)
        if dim is None and field.params is not None:
            dim = field.params.get("dim")
        return dim
