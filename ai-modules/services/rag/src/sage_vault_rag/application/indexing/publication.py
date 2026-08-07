"""单文档向量原子发布与失败补偿（indexing 内部深模块）。

把“准备向量 -> 原子发布 -> 失败补偿”收敛在一个 publication module：成功写入与
失败清理的 seam 明确，回调只报告结果、不参与补偿决策。保持 Java 权威任务状态，
不在 Python 复制状态机；本模块只保证“完整成功才可检索”的原子发布语义。
"""

import asyncio
import logging

from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.model.parsed_document import ParsedDocument
from sage_vault_rag.ports.chunker import ChunkerPort
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.vector_store import VectorStorePort

logger = logging.getLogger(__name__)


class DocumentPublisher:
    """准备向量并原子发布；失败时由补偿路径清理，确保文档不可检索。

    发布语义：先清理该文档上次尝试的残留向量，再原子写入本次完整向量集。
    ``publish`` 抛出的异常由调用方统一进入 ``compensate``；同一文档的发布与
    补偿通过每文档锁串行，避免并发 attempt 把两套片段混入同一个文档。
    """

    def __init__(
        self,
        chunker: ChunkerPort,
        embedder: EmbeddingPort,
        vector_store: VectorStorePort,
    ) -> None:
        self._chunker = chunker
        self._embedder = embedder
        self._vector_store = vector_store
        self._document_locks: dict[str, asyncio.Lock] = {}

    def _lock_for(self, document_id: str) -> asyncio.Lock:
        lock = self._document_locks.get(document_id)
        if lock is None:
            lock = asyncio.Lock()
            self._document_locks[document_id] = lock
        return lock

    async def publish(self, command: IndexingCommand, document: ParsedDocument) -> int:
        """切块、嵌入并原子发布；返回发布的片段数。

        嵌入失败不会触发任何写入；发布阶段（清理旧向量 + 写入新向量）在同一
        文档锁内串行，保证最终只存在本次成功写入的一套完整片段。
        """
        chunks = self._chunker.split(
            document,
            command.knowledge_base_id,
            command.document_id,
            command.filename,
        )
        vectors = await self._embedder.embed([chunk.text for chunk in chunks])
        async with self._lock_for(command.document_id):
            await self._vector_store.delete_by_document(command.document_id)
            await self._vector_store.save_chunks(chunks, vectors)
        return len(chunks)

    async def compensate(self, document_id: str) -> None:
        """幂等删除该文档的全部向量；失败只记录日志，不掩盖原始发布失败。"""
        async with self._lock_for(document_id):
            try:
                await self._vector_store.delete_by_document(document_id)
            except Exception:
                logger.exception("清理 Milvus 向量失败: document_id=%s", document_id)
