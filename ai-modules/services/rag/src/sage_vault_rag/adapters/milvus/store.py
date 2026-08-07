"""Milvus 向量存储适配器：在 VectorStorePort 后编排三根内部 seam。

collection lifecycle（连接/schema/索引）、record codec（实体编解码）与
query builder（表达式转义）各自收敛；应用仍只见单一 VectorStorePort。
保持 ADR-0001 的单 Collection + knowledgeBaseId 过滤决策。
"""

from sage_vault_rag.adapters.milvus.collection import CollectionLifecycle
from sage_vault_rag.adapters.milvus.query_builder import QueryBuilder
from sage_vault_rag.adapters.milvus.record_codec import RecordCodec
from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk


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
        self._lifecycle = CollectionLifecycle(host, port, collection_name, vector_dim, alias)
        self._codec = RecordCodec()
        self._queries = QueryBuilder()

    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        if len(chunks) != len(vectors):
            raise ValueError("chunks 与 vectors 长度不一致")
        if not chunks:
            return
        collection = self._lifecycle.get_collection()
        collection.insert(self._codec.encode(chunks, vectors))
        collection.flush()

    async def delete_by_document(self, document_id: str) -> None:
        collection = self._lifecycle.get_collection()
        collection.load()
        collection.delete(expr=self._queries.document_expr(document_id))
        collection.flush()

    async def count_by_document(self, document_id: str) -> int:
        collection = self._lifecycle.get_collection()
        collection.load()
        page_size = 16384
        total = 0
        offset = 0
        expr = self._queries.document_expr(document_id)
        while True:
            result = collection.query(
                expr=expr,
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
        collection = self._lifecycle.get_collection()
        collection.load()
        results = collection.search(
            data=[vector],
            anns_field="vector",
            param={"metric_type": "L2", "params": {}},
            limit=top_k,
            expr=self._queries.knowledge_base_filter(knowledge_base_id),
            output_fields=self._codec.search_output_fields,
        )
        chunks: list[RetrievedChunk] = []
        for hits in results:
            chunks.extend(self._codec.decode(hits))
        return chunks
