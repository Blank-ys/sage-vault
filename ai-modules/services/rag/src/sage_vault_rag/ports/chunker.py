from typing import Protocol

from sage_vault_rag.model.chunk import Chunk


class ChunkerPort(Protocol):
    """将纯文本切分为带元数据的片段。"""

    def split(self, text: str, knowledge_base_id: int, document_id: str, filename: str) -> list[Chunk]:
        """返回按自然段和长度/重叠参数切分的片段列表。"""
        ...
