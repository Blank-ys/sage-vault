import hashlib
import hmac
import json

import httpx
import pytest

from sage_vault_rag.adapters.java_callback.callback import JavaCallbackClient
from sage_vault_rag.model.indexing_result import IndexingResult


class _CaptureTransport(httpx.AsyncBaseTransport):
    def __init__(self) -> None:
        self.request: httpx.Request | None = None

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        self.request = request
        return httpx.Response(200)


@pytest.mark.asyncio
async def test_callback_posts_signed_result() -> None:
    transport = _CaptureTransport()
    http_client = httpx.AsyncClient(transport=transport)
    client = JavaCallbackClient(
        callback_url="http://java.test/internal/v1/indexing/callbacks",
        signing_key="test-key",
        client=http_client,
    )
    result = IndexingResult(
        task_id="task-1",
        attempt=1,
        document_id="doc-1",
        success=True,
        chunks_count=4,
        diagnostics={"filename": "test.txt"},
    )

    await client.report(result)

    assert transport.request is not None
    request = transport.request
    payload = json.loads(request.content)
    assert payload["taskId"] == "task-1"
    assert payload["attempt"] == 1
    assert payload["success"] is True
    assert "X-Sage-Timestamp" in request.headers
    assert "X-Sage-Signature" in request.headers
    timestamp = request.headers["X-Sage-Timestamp"]
    expected = (
        f"{payload['taskId']}:{payload['attempt']}:{payload['documentId']}:"
        f"{payload['success']}:{payload['chunksCount']}:{payload['requestId']}:{timestamp}"
    ).encode()
    assert hmac.compare_digest(
        request.headers["X-Sage-Signature"],
        hmac.new(b"test-key", expected, hashlib.sha256).hexdigest(),
    )


@pytest.mark.asyncio
async def test_callback_skips_when_url_is_not_configured() -> None:
    client = JavaCallbackClient(callback_url="", signing_key="test-key")
    result = IndexingResult(task_id="task-1", attempt=1, document_id="doc-1", success=True)

    await client.report(result)
