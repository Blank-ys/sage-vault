"""测试模式故障注入适配器包装。

仅在系统验收测试环境下通过环境变量 ``SAGE_VAULT_RAG_TEST_FAILURE_FLAG_FILE``
启用；生产环境不设置该变量，``wrap_with_failure_injection`` 返回原始适配器。
每个包装器在方法调用时读取 flag 文件，若内容与自身阶段匹配则抛出
``RuntimeError``，模拟对应阶段失败；否则委托给真实适配器。
"""

from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.parsed_document import ParsedDocument
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk
from sage_vault_rag.ports.chunker import ChunkerPort
from sage_vault_rag.ports.document_parser import DocumentParserPort
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.vector_store import VectorStorePort

_VALID_STAGES = ("parse", "chunk", "embed", "vector")


def _read_failure_stage(flag_file: str) -> str | None:
    """读取 flag 文件，返回需要注入失败的阶段名。"""
    if not flag_file:
        return None
    try:
        with open(flag_file, encoding="utf-8") as handle:
            stage = handle.read().strip()
    except (FileNotFoundError, OSError):
        return None
    if stage in _VALID_STAGES:
        return stage
    return None


class FailureInjectingDocumentParser:
    """包装 DocumentParserPort，在 parse 阶段注入失败。"""

    def __init__(self, delegate: DocumentParserPort, flag_file: str) -> None:
        self._delegate = delegate
        self._flag_file = flag_file

    async def parse(self, content: bytes, filename: str) -> ParsedDocument:
        if _read_failure_stage(self._flag_file) == "parse":
            raise RuntimeError("test-injected failure: parse")
        return await self._delegate.parse(content, filename)


class FailureInjectingChunker:
    """包装 ChunkerPort，在 chunk 阶段注入失败。"""

    def __init__(self, delegate: ChunkerPort, flag_file: str) -> None:
        self._delegate = delegate
        self._flag_file = flag_file

    def split(
        self,
        document: ParsedDocument,
        knowledge_base_id: int,
        document_id: str,
        filename: str,
    ) -> list[Chunk]:
        if _read_failure_stage(self._flag_file) == "chunk":
            raise RuntimeError("test-injected failure: chunk")
        return self._delegate.split(document, knowledge_base_id, document_id, filename)


class FailureInjectingEmbedder:
    """包装 EmbeddingPort，在 embed 阶段注入失败。"""

    def __init__(self, delegate: EmbeddingPort, flag_file: str) -> None:
        self._delegate = delegate
        self._flag_file = flag_file

    async def embed(self, texts: list[str]) -> list[list[float]]:
        if _read_failure_stage(self._flag_file) == "embed":
            raise RuntimeError("test-injected failure: embed")
        return await self._delegate.embed(texts)

    async def ready(self) -> bool:
        return await self._delegate.ready()


class FailureInjectingVectorStore:
    """包装 VectorStorePort，在 vector 阶段（save_chunks）注入失败。"""

    def __init__(self, delegate: VectorStorePort, flag_file: str) -> None:
        self._delegate = delegate
        self._flag_file = flag_file

    async def save_chunks(self, chunks: list[Chunk], vectors: list[list[float]]) -> None:
        if _read_failure_stage(self._flag_file) == "vector":
            raise RuntimeError("test-injected failure: vector")
        await self._delegate.save_chunks(chunks, vectors)

    async def delete_by_document(self, document_id: str) -> None:
        await self._delegate.delete_by_document(document_id)

    async def search(
        self,
        knowledge_base_id: int,
        vector: list[float],
        top_k: int,
    ) -> list[RetrievedChunk]:
        return await self._delegate.search(knowledge_base_id, vector, top_k)


def wrap_with_failure_injection(
    parser: DocumentParserPort,
    chunker: ChunkerPort,
    embedder: EmbeddingPort,
    vector_store: VectorStorePort,
    flag_file: str,
) -> tuple[DocumentParserPort, ChunkerPort, EmbeddingPort, VectorStorePort]:
    """当 flag_file 非空时，用故障注入包装器包装各适配器；否则原样返回。"""
    if not flag_file:
        return parser, chunker, embedder, vector_store
    return (
        FailureInjectingDocumentParser(parser, flag_file),
        FailureInjectingChunker(chunker, flag_file),
        FailureInjectingEmbedder(embedder, flag_file),
        FailureInjectingVectorStore(vector_store, flag_file),
    )
