from typing import Protocol

from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk


class VectorStorePort(Protocol):
    """向量库存储、清理与检索。"""

    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        """原子写入带向量的片段；失败时抛出异常。"""
        ...

    async def delete_by_document(self, document_id: str) -> None:
        """幂等删除指定文档的全部向量。"""
        ...

    async def search(
        self,
        knowledge_base_id: int,
        vector: list[float],
        top_k: int,
    ) -> list[RetrievedChunk]:
        """按知识库 ID 强制过滤，返回按向量距离升序排列的召回片段。"""
        ...
