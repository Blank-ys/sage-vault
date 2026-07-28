import hashlib
import hmac
import json
import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Protocol

from fastapi import BackgroundTasks, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from sage_vault_rag.adapters.bge_m3.embedder import BgeM3Embedder
from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.adapters.document_parser.dispatcher import FormatDispatchingDocumentParser
from sage_vault_rag.adapters.document_storage.http_client import HttpDocumentStorage
from sage_vault_rag.adapters.fake_generation.generator import FakeGenerationAdapter
from sage_vault_rag.adapters.java_callback.callback import JavaCallbackClient
from sage_vault_rag.adapters.markdown_parser.parser import MarkdownParser
from sage_vault_rag.adapters.milvus.store import MilvusVectorStore
from sage_vault_rag.adapters.nacos.registration import NacosRegistration
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.application.indexing.service import IndexingService
from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.model.events import Completed, Delta, Refused, Started
from sage_vault_rag.model.indexing_command import IndexingCommand

logger = logging.getLogger(__name__)


class AnswerRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    knowledge_base_id: int = Field(alias="knowledgeBaseId", gt=0)
    question: str = Field(min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    generation_id: str = Field(alias="generationId", min_length=1)


class IndexingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    task_id: str = Field(alias="taskId", min_length=1)
    attempt: int = Field(ge=1)
    knowledge_base_id: int = Field(alias="knowledgeBaseId", gt=0)
    document_id: str = Field(alias="documentId", min_length=1)
    filename: str = Field(min_length=1)
    source_url: str = Field(alias="sourceUrl", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)


class ServiceRegistration(Protocol):
    async def register(self) -> None: ...

    async def close(self) -> None: ...


class IndexingRunner(Protocol):
    async def run(self, command: IndexingCommand) -> None: ...


class _IndexingServiceAdapter:
    def __init__(self, service: IndexingService) -> None:
        self._service = service

    async def run(self, command: IndexingCommand) -> None:
        await self._service.index(command)


def create_app(
    settings: Settings,
    answering: AnsweringService | None = None,
    indexing: IndexingRunner | None = None,
    registration: ServiceRegistration | None = None,
) -> FastAPI:
    service_registration = registration or NacosRegistration(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await service_registration.register()
        try:
            yield
        finally:
            await service_registration.close()

    app = FastAPI(title="Sage Vault RAG", lifespan=lifespan)
    answer_service = answering or build_answering_service(settings)
    indexing_runner = indexing or _IndexingServiceAdapter(build_indexing_service(settings))
    seen_requests: dict[str, int] = {}
    seen_indexing_requests: dict[str, int] = {}

    @app.post("/internal/v1/answers", response_class=StreamingResponse)
    async def answer(
        request: AnswerRequest,
        x_sage_timestamp: str = Header(),
        x_sage_signature: str = Header(),
    ) -> StreamingResponse:
        verify_signature(request, x_sage_timestamp, x_sage_signature, settings, seen_requests)

        async def events() -> AsyncIterator[str]:
            async for event in answer_service.answer(
                request.knowledge_base_id, request.question, request.generation_id
            ):
                if isinstance(event, Started):
                    payload = {"type": "started", "generationId": event.generation_id}
                elif isinstance(event, Delta):
                    payload = {"type": "delta", "generationId": event.generation_id, "delta": event.delta}
                elif isinstance(event, Completed):
                    payload = {"type": "completed", "generationId": event.generation_id}
                elif isinstance(event, Refused):
                    payload = {"type": "refused", "generationId": event.generation_id, "message": event.message}
                yield f"event: {payload['type']}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"

        return StreamingResponse(events(), media_type="text/event-stream")

    @app.post("/internal/v1/indexing", status_code=202)
    async def index(
        request: IndexingRequest,
        background_tasks: BackgroundTasks,
        x_sage_timestamp: str = Header(),
        x_sage_signature: str = Header(),
    ) -> None:
        verify_indexing_signature(request, x_sage_timestamp, x_sage_signature, settings, seen_indexing_requests)
        command = IndexingCommand(
            task_id=request.task_id,
            attempt=request.attempt,
            knowledge_base_id=request.knowledge_base_id,
            document_id=request.document_id,
            filename=request.filename,
            source_url=request.source_url,
            request_id=request.request_id,
        )
        background_tasks.add_task(_run_indexing, indexing_runner, command)

    return app


async def _run_indexing(runner: IndexingRunner, command: IndexingCommand) -> None:
    try:
        await runner.run(command)
    except Exception:
        logger.exception("Indexing task failed: task_id=%s attempt=%s", command.task_id, command.attempt)


def build_indexing_service(settings: Settings) -> IndexingService:
    return IndexingService(
        document_storage=HttpDocumentStorage(),
        document_parser=FormatDispatchingDocumentParser(
            {
                "txt": TxtParser(),
                "md": MarkdownParser(),
            }
        ),
        chunker=ParagraphChunker(chunk_size=settings.chunk_size, chunk_overlap=settings.chunk_overlap),
        embedder=_build_embedder(settings),
        vector_store=_build_vector_store(settings),
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
        generator=FakeGenerationAdapter(delta_length=settings.answer_delta_length),
        top_k=settings.retrieval_top_k,
        refusal_threshold=settings.retrieval_refusal_threshold,
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


def verify_signature(
    request: AnswerRequest,
    timestamp: str,
    signature: str,
    settings: Settings,
    seen_requests: dict[str, int],
) -> None:
    try:
        issued_at = int(timestamp)
    except ValueError as exception:
        raise HTTPException(status_code=401, detail="invalid deployment signature") from exception
    if abs(int(time.time()) - issued_at) > settings.replay_window_seconds:
        raise HTTPException(status_code=401, detail="invalid deployment signature")
    now = int(time.time())
    expired = [key for key, expires_at in seen_requests.items() if expires_at < now]
    for key in expired:
        del seen_requests[key]
    replay_key = f"{request.request_id}:{request.generation_id}"
    if replay_key in seen_requests:
        raise HTTPException(status_code=401, detail="invalid deployment signature")
    question_hash = hashlib.sha256(request.question.encode()).hexdigest()
    value = (
        f"{request.knowledge_base_id}:{request.request_id}:{request.generation_id}:{timestamp}:{question_hash}"
    ).encode()
    expected = hmac.new(settings.signing_key.encode(), value, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        raise HTTPException(status_code=401, detail="invalid deployment signature")
    seen_requests[replay_key] = now + settings.replay_window_seconds


def verify_indexing_signature(
    request: IndexingRequest,
    timestamp: str,
    signature: str,
    settings: Settings,
    seen_requests: dict[str, int],
) -> None:
    try:
        issued_at = int(timestamp)
    except ValueError as exception:
        raise HTTPException(status_code=401, detail="invalid deployment signature") from exception
    if abs(int(time.time()) - issued_at) > settings.replay_window_seconds:
        raise HTTPException(status_code=401, detail="invalid deployment signature")
    now = int(time.time())
    expired = [key for key, expires_at in seen_requests.items() if expires_at < now]
    for key in expired:
        del seen_requests[key]
    replay_key = f"{request.request_id}:{request.task_id}:{request.attempt}"
    if replay_key in seen_requests:
        raise HTTPException(status_code=401, detail="invalid deployment signature")
    value = (
        f"{request.knowledge_base_id}:{request.document_id}:{request.task_id}:"
        f"{request.attempt}:{request.request_id}:{timestamp}"
    ).encode()
    expected = hmac.new(settings.signing_key.encode(), value, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        raise HTTPException(status_code=401, detail="invalid deployment signature")
    seen_requests[replay_key] = now + settings.replay_window_seconds
