import logging

from sage_vault_rag.application.indexing.publication import DocumentPublisher
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.indexing_result import IndexingResult
from sage_vault_rag.ports.callback import CallbackPort
from sage_vault_rag.ports.chunker import ChunkerPort
from sage_vault_rag.ports.document_parser import DocumentParserPort
from sage_vault_rag.ports.document_storage import DocumentStoragePort
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.vector_store import VectorStorePort

logger = logging.getLogger(__name__)


class IndexingService:
    """单文档入库流程编排：读取文档、原子发布、失败补偿、回调报告。

    发布（prepare/publish/补偿）收敛在内部 ``DocumentPublisher`` publication module；
    callback 只报告结果，不参与补偿决策。任意步骤失败仍走补偿与失败回调路径，
    失败原因记录在 ``IndexingResult.diagnostics``，不向 Java 暴露异常细节。
    """

    def __init__(
        self,
        document_storage: DocumentStoragePort,
        document_parser: DocumentParserPort,
        chunker: ChunkerPort,
        embedder: EmbeddingPort,
        vector_store: VectorStorePort,
        callback: CallbackPort,
    ) -> None:
        self._document_storage = document_storage
        self._document_parser = document_parser
        self._publisher = DocumentPublisher(chunker, embedder, vector_store)
        self._callback = callback

    async def index(self, command: IndexingCommand) -> IndexingResult:
        try:
            content = await self._document_storage.download(command.source_url)
            document = await self._document_parser.parse(content, command.filename)
            chunks_count = await self._publisher.publish(command, document)
            result = IndexingResult(
                task_id=command.task_id,
                attempt=command.attempt,
                document_id=command.document_id,
                success=True,
                chunks_count=chunks_count,
                diagnostics={"filename": command.filename},
            )
        except Exception as exception:
            logger.exception(
                "入库失败: task_id=%s attempt=%s document_id=%s",
                command.task_id,
                command.attempt,
                command.document_id,
            )
            await self._publisher.compensate(command.document_id)
            result = IndexingResult(
                task_id=command.task_id,
                attempt=command.attempt,
                document_id=command.document_id,
                success=False,
                chunks_count=0,
                diagnostics={"error": type(exception).__name__, "filename": command.filename},
            )
        await self._callback.report(result)
        return result
