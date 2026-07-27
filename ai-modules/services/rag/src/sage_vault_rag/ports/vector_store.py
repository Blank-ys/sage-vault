from typing import Protocol

from sage_vault_rag.model.chunk import Chunk


class VectorStorePort(Protocol):
    """向量库存储与清理。"""

    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        """原子写入带向量的片段；失败时抛出异常。"""
        ...

    async def delete_by_document(self, document_id: str) -> None:
        """幂等删除指定文档的全部向量。"""
        ...
