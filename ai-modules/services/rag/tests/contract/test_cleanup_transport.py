import hashlib
import hmac
import time
from typing import Any

from fastapi.testclient import TestClient

from sage_vault_rag.bootstrap.dependencies import CleanupRunner
from sage_vault_rag.model.cleanup_command import CleanupCommand
from sage_vault_rag.transport.http.app import create_app
from tests.contract._fakes import NoOpRegistration, dependencies_for_test, settings_for_test


class CapturingCleanupRunner(CleanupRunner):
    def __init__(self) -> None:
        self.commands: list[CleanupCommand] = []

    async def run(self, command: CleanupCommand) -> None:
        self.commands.append(command)


def _sign(payload: dict[str, Any], timestamp: str, key: str = "test-key") -> str:
    value = (
        f"{payload['knowledgeBaseId']}:{payload['documentId']}:{payload['taskId']}:"
        f"{payload['requestId']}:{timestamp}"
    ).encode()
    return hmac.new(key.encode(), value, hashlib.sha256).hexdigest()


def test_signed_cleanup_command_returns_202_and_runs_in_background() -> None:
    runner = CapturingCleanupRunner()
    client = TestClient(create_app(settings_for_test(), dependencies=dependencies_for_test(cleanup=runner), registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    payload = {
        "taskId": "task-clean-1",
        "knowledgeBaseId": 7,
        "documentId": "doc-1",
        "requestId": "req-clean-1",
    }

    response = client.post(
        "/internal/v1/cleanup",
        headers={"X-Sage-Timestamp": timestamp, "X-Sage-Signature": _sign(payload, timestamp)},
        json=payload,
    )

    assert response.status_code == 202
    assert len(runner.commands) == 1
    command = runner.commands[0]
    assert command.task_id == "task-clean-1"
    assert command.knowledge_base_id == 7
    assert command.document_id == "doc-1"


def test_unsigned_cleanup_command_is_rejected() -> None:
    client = TestClient(create_app(settings_for_test(), dependencies=dependencies_for_test(cleanup=CapturingCleanupRunner()), registration=NoOpRegistration()))
    response = client.post(
        "/internal/v1/cleanup",
        headers={"X-Sage-Timestamp": str(int(time.time())), "X-Sage-Signature": "wrong"},
        json={
            "taskId": "task-clean-1",
            "knowledgeBaseId": 7,
            "documentId": "doc-1",
            "requestId": "req-clean-1",
        },
    )
    assert response.status_code == 401


def test_signed_cleanup_command_cannot_be_replayed() -> None:
    client = TestClient(create_app(settings_for_test(), dependencies=dependencies_for_test(cleanup=CapturingCleanupRunner()), registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    payload = {
        "taskId": "task-clean-1",
        "knowledgeBaseId": 7,
        "documentId": "doc-1",
        "requestId": "req-clean-1",
    }
    signature = _sign(payload, timestamp)

    assert client.post("/internal/v1/cleanup", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 202
    assert client.post("/internal/v1/cleanup", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 401
