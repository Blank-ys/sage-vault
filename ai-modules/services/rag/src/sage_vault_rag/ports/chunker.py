from typing import Protocol

from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.parsed_document import ParsedDocument


class ChunkerPort(Protocol):
    """将结构化文档切分为带元数据的片段，优先保留自然段与标题边界。"""

    def split(
        self,
        document: ParsedDocument,
        knowledge_base_id: int,
        document_id: str,
        filename: str,
    ) -> list[Chunk]:
        """返回按自然段顺序、长度/重叠参数切分的片段列表。"""
        ...
