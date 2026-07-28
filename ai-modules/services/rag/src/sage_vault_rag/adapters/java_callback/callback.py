import hashlib
import hmac
import logging
import time
import uuid
from typing import Any

import httpx

from sage_vault_rag.model.indexing_result import IndexingResult

logger = logging.getLogger(__name__)


class JavaCallbackClient:
    """向 Java 报告入库结果，携带部署签名与重放保护。"""

    def __init__(self, callback_url: str, signing_key: str, timeout_seconds: float = 60.0,
            client: httpx.AsyncClient | None = None) -> None:
        self._callback_url = callback_url
        self._signing_key = signing_key
        self._timeout = timeout_seconds
        self._client = client

    async def report(self, result: IndexingResult) -> None:
        if not self._callback_url:
            logger.warning("Java callback URL is not configured; skipping report for task %s", result.task_id)
            return
        timestamp = str(int(time.time()))
        request_id = uuid.uuid4().hex
        payload = {
            "taskId": result.task_id,
            "attempt": result.attempt,
            "documentId": result.document_id,
            "success": result.success,
            "chunksCount": result.chunks_count,
            "requestId": request_id,
            "diagnostics": result.diagnostics,
        }
        signature = self._sign(payload, timestamp)
        headers = {
            "X-Sage-Timestamp": timestamp,
            "X-Sage-Signature": signature,
        }
        if self._client is not None:
            response = await self._client.post(self._callback_url, json=payload, headers=headers)
            response.raise_for_status()
            return
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(self._callback_url, json=payload, headers=headers)
            response.raise_for_status()

    def _sign(self, payload: dict[str, Any], timestamp: str) -> str:
        value = (
            f"{payload['taskId']}:{payload['attempt']}:{payload['documentId']}:"
            f"{payload['success']}:{payload['chunksCount']}:{payload['requestId']}:{timestamp}"
        ).encode()
        return hmac.new(self._signing_key.encode(), value, hashlib.sha256).hexdigest()
