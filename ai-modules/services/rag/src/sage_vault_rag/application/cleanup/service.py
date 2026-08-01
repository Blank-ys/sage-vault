import logging

from sage_vault_rag.model.cleanup_command import CleanupCommand
from sage_vault_rag.model.cleanup_result import CleanupResult
from sage_vault_rag.ports.cleanup_callback import CleanupCallbackPort
from sage_vault_rag.ports.vector_store import VectorStorePort

logger = logging.getLogger(__name__)


class CleanupService:
    """单文档清理流程编排：删除 Milvus 向量、回调 Java。"""

    def __init__(
        self,
        vector_store: VectorStorePort,
        callback: CleanupCallbackPort,
    ) -> None:
        self._vector_store = vector_store
        self._callback = callback

    async def cleanup(self, command: CleanupCommand) -> CleanupResult:
        try:
            await self._vector_store.delete_by_document(command.document_id)
            result = CleanupResult(
                task_id=command.task_id,
                document_id=command.document_id,
                success=True,
            )
        except Exception as exception:
            logger.exception(
                "清理失败: task_id=%s document_id=%s",
                command.task_id,
                command.document_id,
            )
            result = CleanupResult(
                task_id=command.task_id,
                document_id=command.document_id,
                success=False,
                diagnostics={"error": type(exception).__name__},
            )
        await self._callback.report_cleanup(result)
        return result
