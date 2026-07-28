from collections.abc import AsyncIterator
from typing import Protocol

from sage_vault_rag.model.retrieved_chunk import RetrievedChunk


class GenerationPort(Protocol):
    """基于召回片段生成流式回答，不得使用外部知识补答。"""

    def generate(
        self,
        generation_id: str,
        question: str,
        chunks: list[RetrievedChunk],
    ) -> AsyncIterator[str]:
        ...
