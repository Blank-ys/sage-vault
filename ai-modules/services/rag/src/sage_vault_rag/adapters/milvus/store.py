import logging

from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    MilvusException,
    connections,
    utility,
)

from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk

logger = logging.getLogger(__name__)


class MilvusVectorStore:
    """Milvus 向量存储适配器，负责 collection 创建、向量写入与按文档清理。"""

    def __init__(
        self,
        host: str,
        port: int,
        collection_name: str,
        vector_dim: int,
        alias: str = "default",
    ) -> None:
        self._host = host
        self._port = port
        self._collection_name = collection_name
        self._vector_dim = vector_dim
        self._alias = alias
        self._collection: Collection | None = None

    def _connect(self) -> None:
        try:
            connections.connect(alias=self._alias, host=self._host, port=self._port)
        except MilvusException:
            if not connections.has_connection(self._alias):
                raise

    def _get_collection(self) -> Collection:
        if self._collection is not None:
            return self._collection
        self._connect()
        if utility.has_collection(self._collection_name, using=self._alias):
            collection = Collection(self._collection_name, using=self._alias)
            if self._schema_matches(collection):
                self._collection = collection
                return self._collection
            logger.warning(
                "Collection %s schema mismatch; dropping and recreating. "
                "Expected fields: %s, existing fields: %s",
                self._collection_name,
                [field.name for field in self._build_schema().fields],
                [field.name for field in collection.schema.fields],
            )
            utility.drop_collection(self._collection_name, using=self._alias)
        schema = self._build_schema()
        self._collection = Collection(
            name=self._collection_name,
            schema=schema,
            using=self._alias,
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
        # 表示"无元数据"，由 save_chunks/search 在适配器边界与 None 双向转换。
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

    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        if len(chunks) != len(vectors):
            raise ValueError("chunks 与 vectors 长度不一致")
        if not chunks:
            return
        collection = self._get_collection()
        entities = [
            [chunk.chunk_id for chunk in chunks],
            [chunk.knowledge_base_id for chunk in chunks],
            [chunk.document_id for chunk in chunks],
            [chunk.filename for chunk in chunks],
            [chunk.sequence for chunk in chunks],
            [chunk.text for chunk in chunks],
            [chunk.section_title if chunk.section_title is not None else "" for chunk in chunks],
            [chunk.page_number if chunk.page_number is not None else 0 for chunk in chunks],
            vectors,
        ]
        collection.insert(entities)
        collection.flush()

    async def delete_by_document(self, document_id: str) -> None:
        collection = self._get_collection()
        collection.delete(expr=self._document_expr(document_id))
        collection.flush()

    async def count_by_document(self, document_id: str) -> int:
        collection = self._get_collection()
        collection.load()
        page_size = 16384
        total = 0
        offset = 0
        while True:
            result = collection.query(
                expr=self._document_expr(document_id),
                output_fields=["chunk_id"],
                limit=page_size,
                offset=offset,
            )
            total += len(result)
            if len(result) < page_size:
                break
            offset += page_size
        return total

    async def search(
        self,
        knowledge_base_id: int,
        vector: list[float],
        top_k: int,
    ) -> list[RetrievedChunk]:
        collection = self._get_collection()
        collection.load()
        results = collection.search(
            data=[vector],
            anns_field="vector",
            param={"metric_type": "L2", "params": {}},
            limit=top_k,
            expr=f"knowledge_base_id == {knowledge_base_id}",
            output_fields=["chunk_id", "document_id", "filename", "sequence", "text", "section_title", "page_number"],
        )
        chunks: list[RetrievedChunk] = []
        for hits in results:
            for hit in hits:
                section_title_raw = hit.entity.get("section_title")
                page_number_raw = hit.entity.get("page_number")
                chunks.append(
                    RetrievedChunk(
                        chunk_id=hit.entity.get("chunk_id"),
                        document_id=hit.entity.get("document_id"),
                        filename=hit.entity.get("filename"),
                        sequence=hit.entity.get("sequence"),
                        text=hit.entity.get("text"),
                        score=float(hit.distance),
                        section_title=section_title_raw if section_title_raw != "" else None,
                        page_number=page_number_raw if page_number_raw != 0 else None,
                    )
                )
        return chunks

    @staticmethod
    def _document_expr(document_id: str) -> str:
        escaped = document_id.replace("\\", "\\\\").replace('"', '\\"')
        return f'document_id == "{escaped}"'
