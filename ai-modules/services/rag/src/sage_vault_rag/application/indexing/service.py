import logging

from sage_vault_rag.model.chunk import Chunk
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
    """单文档入库流程编排：下载、解析、切块、嵌入、写入 Milvus、回调。

    使用 `DocumentParserPort` 获取结构化文档（自然段列表 + 可选标题/页码元数据），
    再交给 `ChunkerPort` 切块。任意步骤失败仍走既有清理与回调路径，
    失败原因记录在 `IndexingResult.diagnostics`，不向 Java 暴露异常细节。
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
        self._chunker = chunker
        self._embedder = embedder
        self._vector_store = vector_store
        self._callback = callback

    async def index(self, command: IndexingCommand) -> IndexingResult:
        chunks: list[Chunk] = []
        try:
            content = await self._document_storage.download(command.source_url)
            document = await self._document_parser.parse(content, command.filename)
            chunks = self._chunker.split(
                document,
                command.knowledge_base_id,
                command.document_id,
                command.filename,
            )
            vectors = await self._embedder.embed([chunk.text for chunk in chunks])
            await self._vector_store.save_chunks(chunks, vectors)
            result = IndexingResult(
                task_id=command.task_id,
                attempt=command.attempt,
                document_id=command.document_id,
                success=True,
                chunks_count=len(chunks),
                diagnostics={"filename": command.filename},
            )
        except Exception as exception:
            logger.exception(
                "入库失败: task_id=%s attempt=%s document_id=%s",
                command.task_id,
                command.attempt,
                command.document_id,
            )
            await self._cleanup(command.document_id, chunks)
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

    async def _cleanup(self, document_id: str, chunks: list[Chunk]) -> None:
        try:
            await self._vector_store.delete_by_document(document_id)
        except Exception:
            logger.exception("清理 Milvus 向量失败: document_id=%s", document_id)
