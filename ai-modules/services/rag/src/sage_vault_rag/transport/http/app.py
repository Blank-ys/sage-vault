import hashlib
import hmac
import json
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Protocol

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from sage_vault_rag.adapters.nacos.registration import NacosRegistration
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.model.events import Refused, Started


class AnswerRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    knowledge_base_id: int = Field(alias="knowledgeBaseId", gt=0)
    question: str = Field(min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    generation_id: str = Field(alias="generationId", min_length=1)


class ServiceRegistration(Protocol):
    async def register(self) -> None: ...

    async def close(self) -> None: ...


def create_app(
    settings: Settings,
    answering: AnsweringService | None = None,
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
    service = answering or AnsweringService()
    seen_requests: dict[str, int] = {}

    @app.post("/internal/v1/answers", response_class=StreamingResponse)
    async def answer(
        request: AnswerRequest,
        x_sage_timestamp: str = Header(),
        x_sage_signature: str = Header(),
    ) -> StreamingResponse:
        verify_signature(request, x_sage_timestamp, x_sage_signature, settings, seen_requests)

        async def events() -> AsyncIterator[str]:
            async for event in service.answer(request.generation_id):
                if isinstance(event, Started):
                    payload = {"type": "started", "generationId": event.generation_id}
                elif isinstance(event, Refused):
                    payload = {"type": "refused", "generationId": event.generation_id, "message": event.message}
                yield f"event: {payload['type']}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"

        return StreamingResponse(events(), media_type="text/event-stream")

    return app


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
