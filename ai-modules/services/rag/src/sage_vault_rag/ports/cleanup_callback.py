from typing import Protocol

from sage_vault_rag.model.cleanup_result import CleanupResult


class CleanupCallbackPort(Protocol):
    """向 Java 报告单文档清理结果。"""

    async def report_cleanup(self, result: CleanupResult) -> None:
        """发送清理回调；失败时抛出异常。"""
        ...
