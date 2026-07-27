import logging

from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.indexing_result import IndexingResult
from sage_vault_rag.ports.callback import CallbackPort
from sage_vault_rag.ports.chunker import ChunkerPort
from sage_vault_rag.ports.document_storage import DocumentStoragePort
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.text_parser import TextParserPort
from sage_vault_rag.ports.vector_store import VectorStorePort

logger = logging.getLogger(__name__)


class IndexingService:
    """单文档入库流程编排：下载、解析、切块、嵌入、写入 Milvus、回调。"""

    def __init__(
        self,
        document_storage: DocumentStoragePort,
        text_parser: TextParserPort,
        chunker: ChunkerPort,
        embedder: EmbeddingPort,
        vector_store: VectorStorePort,
        callback: CallbackPort,
    ) -> None:
        self._document_storage = document_storage
        self._text_parser = text_parser
        self._chunker = chunker
        self._embedder = embedder
        self._vector_store = vector_store
        self._callback = callback

    async def index(self, command: IndexingCommand) -> IndexingResult:
        chunks: list[Chunk] = []
        try:
            content = await self._document_storage.download(command.source_url)
            text = await self._text_parser.parse(content, command.filename)
            chunks = self._chunker.split(
                text,
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
