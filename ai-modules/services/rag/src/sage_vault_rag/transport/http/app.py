import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import BackgroundTasks, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from sage_vault_rag.bootstrap.dependencies import CleanupRunner, IndexingRunner, RagDependencies, ServiceRegistration
from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.model.cleanup_command import CleanupCommand
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.transport.http.events import render_sse
from sage_vault_rag.transport.http.replay_auth import ReplayAuth
from sage_vault_rag.transport.http.schemas import (
    AnswerRequest,
    CancelAnswerRequest,
    CleanupRequest,
    IndexingRequest,
)

logger = logging.getLogger(__name__)


def create_app(
    settings: Settings,
    dependencies: RagDependencies | None = None,
    registration: ServiceRegistration | None = None,
) -> FastAPI:
    """创建 HTTP transport app；只消费 application-facing interface。

    生产入口由 bootstrap 组装依赖与 Nacos 注册；契约测试注入 fake adapters。
    transport 不直接导入 Milvus、BGE、DashScope、MinIO 或 Nacos 的具体实现。
    """
    if dependencies is None:
        raise ValueError("dependencies 是必需的；请通过 sage_vault_rag.bootstrap.factories.build_dependencies 组装")
    if registration is None:
        raise ValueError("registration 是必需的；请传入 NacosRegistration 或测试替身")

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await registration.register()
        try:
            yield
        finally:
            await registration.close()

    app = FastAPI(title="Sage Vault RAG", lifespan=lifespan)
    answer_service = dependencies.answering
    indexing_runner = dependencies.indexing
    cleanup_runner = dependencies.cleanup
    auth = ReplayAuth(settings.signing_key, settings.replay_window_seconds)

    @app.post("/internal/v1/answers", response_class=StreamingResponse)
    async def answer(
        request: AnswerRequest,
        x_sage_timestamp: str = Header(),
        x_sage_signature: str = Header(),
    ) -> StreamingResponse:
        auth.verify_answer(request, x_sage_timestamp, x_sage_signature)

        async def events() -> AsyncIterator[str]:
            async for event in answer_service.answer(
                request.knowledge_base_id, request.question, request.generation_id
            ):
                yield render_sse(event)

        return StreamingResponse(events(), media_type="text/event-stream")

    @app.post("/internal/v1/answers/{generationId}/cancel", status_code=202)
    async def cancel_answer(
        generationId: str,
        request: CancelAnswerRequest,
        x_sage_timestamp: str = Header(),
        x_sage_signature: str = Header(),
    ) -> dict[str, object]:
        if generationId != request.generation_id:
            raise HTTPException(status_code=401, detail="invalid deployment signature")
        auth.verify_cancel(request, x_sage_timestamp, x_sage_signature)
        cancelled = answer_service.cancellations.cancel(request.generation_id)
        if not cancelled:
            logger.info(
                "Cancel ignored, generation not owned here: generation_id=%s request_id=%s",
                request.generation_id,
                request.request_id,
            )
        return {"generationId": request.generation_id, "cancelled": cancelled}

    @app.post("/internal/v1/indexing", status_code=202)
    async def index(
        request: IndexingRequest,
        background_tasks: BackgroundTasks,
        x_sage_timestamp: str = Header(),
        x_sage_signature: str = Header(),
    ) -> None:
        auth.verify_indexing(request, x_sage_timestamp, x_sage_signature)
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

    @app.post("/internal/v1/cleanup", status_code=202)
    async def cleanup_document(
        request: CleanupRequest,
        background_tasks: BackgroundTasks,
        x_sage_timestamp: str = Header(),
        x_sage_signature: str = Header(),
    ) -> None:
        auth.verify_cleanup(request, x_sage_timestamp, x_sage_signature)
        command = CleanupCommand(
            task_id=request.task_id,
            knowledge_base_id=request.knowledge_base_id,
            document_id=request.document_id,
            request_id=request.request_id,
        )
        background_tasks.add_task(_run_cleanup, cleanup_runner, command)

    return app


async def _run_indexing(runner: IndexingRunner, command: IndexingCommand) -> None:
    try:
        await runner.run(command)
    except Exception:
        logger.exception("Indexing task failed: task_id=%s attempt=%s", command.task_id, command.attempt)


async def _run_cleanup(runner: CleanupRunner, command: CleanupCommand) -> None:
    try:
        await runner.run(command)
    except Exception:
        logger.exception("Cleanup task failed: task_id=%s document_id=%s", command.task_id, command.document_id)
