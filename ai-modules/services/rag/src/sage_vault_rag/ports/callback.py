from typing import Protocol

from sage_vault_rag.model.indexing_result import IndexingResult


class CallbackPort(Protocol):
    """向 Java 报告单文档入库结果。

    回调只报告结果，不参与发布/补偿决策：回调失败不会触发向量清理，
    已成功发布的内容保持不变。
    """

    async def report(self, result: IndexingResult) -> None:
        """发送回调；失败时抛出异常。"""
        ...
