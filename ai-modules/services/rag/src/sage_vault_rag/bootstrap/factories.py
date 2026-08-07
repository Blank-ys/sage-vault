"""具体 adapter -> application services 的对象图组装。

bootstrap 只负责依赖组装与资源生命周期，不裁决业务终态。
"""

from sage_vault_rag.adapters.bge_m3.embedder import BgeM3Embedder
from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.dashscope.generator import DashScopeGenerationAdapter
from sage_vault_rag.adapters.document_parser.dispatcher import FormatDispatchingDocumentParser
from sage_vault_rag.adapters.document_storage.http_client import HttpDocumentStorage
from sage_vault_rag.adapters.docx_parser.parser import DocxParser
from sage_vault_rag.adapters.failure_injection.wrappers import wrap_with_failure_injection
from sage_vault_rag.adapters.fake_generation.generator import FakeGenerationAdapter
from sage_vault_rag.adapters.java_callback.callback import JavaCallbackClient
from sage_vault_rag.adapters.markdown_parser.parser import MarkdownParser
from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.adapters.pdf_parser.parser import PdfParser
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.application.cleanup.service import CleanupService
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.bootstrap.dependencies import RagDependencies
from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.model.cleanup_command import CleanupCommand
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.ports.chunker import ChunkerPort
from sage_vault_rag.ports.document_parser import DocumentParserPort
from sage_vault_rag.ports.embedding import EmbeddingPort
from sage_vault_rag.ports.generation import GenerationPort
from sage_vault_rag.ports.vector_store import VectorStorePort


class _IndexingServiceAdapter:
    def __init__(self, service: IndexingService) -> None:
        self._service = service

    async def run(self, command: IndexingCommand) -> None:
        await self._service.index(command)


class _CleanupServiceAdapter:
    def __init__(self, service: CleanupService) -> None:
        self._service = service

    async def run(self, command: CleanupCommand) -> None:
        await self._service.cleanup(command)


def build_dependencies(settings: Settings) -> RagDependencies:
    return RagDependencies(
        answering=build_answering_service(settings),
        indexing=_IndexingServiceAdapter(build_indexing_service(settings)),
        cleanup=_CleanupServiceAdapter(build_cleanup_service(settings)),
    )


def build_indexing_service(settings: Settings) -> IndexingService:
    parser: DocumentParserPort = FormatDispatchingDocumentParser(
        {
            "txt": TxtParser(),
            "md": MarkdownParser(),
            "pdf": PdfParser(),
            "docx": DocxParser(),
        }
    )
    chunker: ChunkerPort = ParagraphChunker(chunk_size=settings.chunk_size, chunk_overlap=settings.chunk_overlap)
    embedder: EmbeddingPort = _build_embedder(settings)
    vector_store: VectorStorePort = _build_vector_store(settings)
    parser, chunker, embedder, vector_store = wrap_with_failure_injection(
        parser, chunker, embedder, vector_store, settings.test_failure_flag_file
    )
    return IndexingService(
        document_storage=HttpDocumentStorage(),
        document_parser=parser,
        chunker=chunker,
        embedder=embedder,
        vector_store=vector_store,
        callback=JavaCallbackClient(
            callback_url=settings.java_callback_url,
            signing_key=settings.java_callback_signing_key,
            timeout_seconds=60.0,
        ),
    )


def build_answering_service(settings: Settings) -> AnsweringService:
    return AnsweringService(
        embedder=_build_embedder(settings),
        vector_store=_build_vector_store(settings),
        generator=_build_generator(settings),
        top_k=settings.retrieval_top_k,
        refusal_threshold=settings.retrieval_refusal_threshold,
    )


def build_cleanup_service(settings: Settings) -> CleanupService:
    return CleanupService(
        vector_store=_build_vector_store(settings),
        callback=JavaCallbackClient(
            callback_url=settings.java_callback_url,
            signing_key=settings.java_callback_signing_key,
            timeout_seconds=60.0,
            cleanup_callback_url=settings.java_cleanup_callback_url,
        ),
    )


def _build_embedder(settings: Settings) -> BgeM3Embedder:
    return BgeM3Embedder(
        model_path=settings.embedding_model_path,
        profile=settings.embedding_profile,
        batch_size=settings.embedding_batch_size,
        max_length=settings.embedding_max_length,
        max_concurrent_requests=settings.embedding_max_concurrent_requests,
        max_queue_size=settings.embedding_max_queue_size,
    )


def _build_vector_store(settings: Settings) -> MilvusVectorStore:
    return MilvusVectorStore(
        host=settings.milvus_host,
        port=settings.milvus_port,
        collection_name=settings.milvus_collection_name,
        vector_dim=settings.milvus_vector_dim,
    )


def _build_generator(settings: Settings) -> GenerationPort:
    provider = settings.generation_provider
    if provider == "fake":
        return FakeGenerationAdapter(
            delta_length=settings.answer_delta_length,
            delta_interval_seconds=settings.answer_delta_interval_seconds,
        )
    if provider == "bailian":
        return DashScopeGenerationAdapter(
            api_key=settings.bailian_api_key,
            model=settings.bailian_model,
            max_tokens=settings.bailian_max_tokens,
            temperature=settings.bailian_temperature,
            timeout=settings.bailian_timeout_seconds,
            base_url=settings.bailian_base_url,
        )
    raise ValueError(f"未知的 generation_provider: {provider}")
