import asyncio
from collections.abc import AsyncIterator

from sage_vault_rag.model.retrieved_chunk import RetrievedChunk
from sage_vault_rag.ports.generation import GenerationPort


class FakeGenerationAdapter(GenerationPort):
    """确定性假生成适配器：仅基于召回片段拼接文本并按固定长度输出 delta。"""

    def __init__(self, delta_length: int = 5, delta_interval_seconds: float = 0.0) -> None:
        if delta_length <= 0:
            raise ValueError("delta_length 必须大于 0")
        if delta_interval_seconds < 0:
            raise ValueError("delta_interval_seconds 不能为负数")
        self._delta_length = delta_length
        self._delta_interval_seconds = delta_interval_seconds

    async def generate(
        self,
        generation_id: str,
        question: str,
        chunks: list[RetrievedChunk],
    ) -> AsyncIterator[str]:
        evidence = "\n\n".join(chunk.text for chunk in chunks)
        for start in range(0, len(evidence), self._delta_length):
            # 让出事件循环，使在途取消命令能够被及时处理
            await asyncio.sleep(self._delta_interval_seconds)
            yield evidence[start : start + self._delta_length]
