from typing import Protocol


class EmbeddingPort(Protocol):
    """本地嵌入模型，生成归一化稠密向量。"""

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """为文本列表返回等长向量列表。"""
        ...

    async def ready(self) -> bool:
        """返回模型是否已加载并可用。"""
        ...
