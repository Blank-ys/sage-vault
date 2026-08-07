"""内部调用的部署签名与重放保护。

统一收拢 answer / cancel / indexing / cleanup 四类调用的时间戳窗口校验、
HMAC 签名比对与重放键唯一性记录。每类调用在此声明其签名字节格式，
并共享同一个校验核心。签名与重放状态按 app 实例持有，不提升为模块级全局变量。
"""

import hashlib
import hmac
import time

from fastapi import HTTPException

from sage_vault_rag.transport.http.schemas import (
    AnswerRequest,
    CancelAnswerRequest,
    CleanupRequest,
    IndexingRequest,
)


def _unauthorized() -> HTTPException:
    return HTTPException(status_code=401, detail="invalid deployment signature")


class ReplayAuth:
    """校验部署签名并防止重放；失败统一返回 HTTP 401。"""

    def __init__(self, signing_key: str, replay_window_seconds: int) -> None:
        self._signing_key = signing_key
        self._replay_window_seconds = replay_window_seconds
        self._seen: dict[str, int] = {}

    def verify_answer(self, request: AnswerRequest, timestamp: str, signature: str) -> None:
        question_hash = hashlib.sha256(request.question.encode()).hexdigest()
        self._verify(
            value_parts=[
                str(request.knowledge_base_id),
                request.request_id,
                request.generation_id,
                timestamp,
                question_hash,
            ],
            replay_key=f"answer:{request.request_id}:{request.generation_id}",
            timestamp=timestamp,
            signature=signature,
        )

    def verify_cancel(self, request: CancelAnswerRequest, timestamp: str, signature: str) -> None:
        self._verify(
            value_parts=["cancel", request.generation_id, request.request_id, timestamp],
            replay_key=f"cancel:{request.request_id}:{request.generation_id}",
            timestamp=timestamp,
            signature=signature,
        )

    def verify_indexing(self, request: IndexingRequest, timestamp: str, signature: str) -> None:
        self._verify(
            value_parts=[
                str(request.knowledge_base_id),
                request.document_id,
                request.task_id,
                str(request.attempt),
                request.request_id,
                timestamp,
            ],
            replay_key=f"indexing:{request.request_id}:{request.task_id}:{request.attempt}",
            timestamp=timestamp,
            signature=signature,
        )

    def verify_cleanup(self, request: CleanupRequest, timestamp: str, signature: str) -> None:
        self._verify(
            value_parts=[
                str(request.knowledge_base_id),
                request.document_id,
                request.task_id,
                request.request_id,
                timestamp,
            ],
            replay_key=f"cleanup:{request.request_id}:{request.task_id}",
            timestamp=timestamp,
            signature=signature,
        )

    def _verify(self, value_parts: list[str], replay_key: str, timestamp: str, signature: str) -> None:
        try:
            issued_at = int(timestamp)
        except ValueError as exception:
            raise _unauthorized() from exception
        now = int(time.time())
        if abs(now - issued_at) > self._replay_window_seconds:
            raise _unauthorized()
        expired = [key for key, expires_at in self._seen.items() if expires_at < now]
        for key in expired:
            del self._seen[key]
        if replay_key in self._seen:
            raise _unauthorized()
        value = ":".join(value_parts).encode()
        expected = hmac.new(self._signing_key.encode(), value, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature):
            raise _unauthorized()
        self._seen[replay_key] = now + self._replay_window_seconds
