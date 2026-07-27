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
            self._collection = Collection(self._collection_name, using=self._alias)
            return self._collection
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
        fields = [
            FieldSchema(name="chunk_id", dtype=DataType.VARCHAR, max_length=64, is_primary=True),
            FieldSchema(name="knowledge_base_id", dtype=DataType.INT64),
            FieldSchema(name="document_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="filename", dtype=DataType.VARCHAR, max_length=512),
            FieldSchema(name="sequence", dtype=DataType.INT64),
            FieldSchema(name="text", dtype=DataType.VARCHAR, max_length=65535),
            FieldSchema(name="vector", dtype=DataType.FLOAT_VECTOR, dim=self._vector_dim),
        ]
        return CollectionSchema(fields, description="Sage Vault document chunks")

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

    @staticmethod
    def _document_expr(document_id: str) -> str:
        escaped = document_id.replace("\\", "\\\\").replace('"', '\\"')
        return f'document_id == "{escaped}"'
