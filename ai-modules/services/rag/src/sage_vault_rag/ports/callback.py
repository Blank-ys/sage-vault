from typing import Protocol

from sage_vault_rag.model.indexing_result import IndexingResult


class CallbackPort(Protocol):
    """向 Java 报告单文档入库结果。"""

    async def report(self, result: IndexingResult) -> None:
        """发送回调；失败时抛出异常。"""
        ...
