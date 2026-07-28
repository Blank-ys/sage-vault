import hashlib
import hmac
import time
from typing import Any

from fastapi.testclient import TestClient

from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.model.indexing_command import IndexingCommand
from sage_vault_rag.transport.http.app import IndexingRunner, create_app


class NoOpRegistration:
    async def register(self) -> None:
        pass

    async def close(self) -> None:
        pass


class CapturingIndexingRunner(IndexingRunner):
    def __init__(self) -> None:
        self.commands: list[IndexingCommand] = []

    async def run(self, command: IndexingCommand) -> None:
        self.commands.append(command)


def settings_for_test() -> Settings:
    return Settings(
        signing_key="test-key",
        nacos_server_address="nacos.test:8848",
        embedding_model_path="/dev/null/model",
    )


def _sign(payload: dict[str, Any], timestamp: str, key: str = "test-key") -> str:
    value = (
        f"{payload['knowledgeBaseId']}:{payload['documentId']}:{payload['taskId']}:"
        f"{payload['attempt']}:{payload['requestId']}:{timestamp}"
    ).encode()
    return hmac.new(key.encode(), value, hashlib.sha256).hexdigest()


def test_signed_indexing_command_returns_202_and_runs_in_background() -> None:
    runner = CapturingIndexingRunner()
    client = TestClient(create_app(settings_for_test(), indexing=runner, registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    payload = {
        "taskId": "task-1",
        "attempt": 1,
        "knowledgeBaseId": 7,
        "documentId": "doc-1",
        "filename": "test.txt",
        "sourceUrl": "http://minio/test.txt",
        "requestId": "req-1",
    }

    response = client.post(
        "/internal/v1/indexing",
        headers={"X-Sage-Timestamp": timestamp, "X-Sage-Signature": _sign(payload, timestamp)},
        json=payload,
    )

    assert response.status_code == 202
    assert len(runner.commands) == 1
    command = runner.commands[0]
    assert command.task_id == "task-1"
    assert command.attempt == 1
    assert command.knowledge_base_id == 7
    assert command.document_id == "doc-1"


def test_unsigned_indexing_command_is_rejected() -> None:
    client = TestClient(create_app(settings_for_test(), registration=NoOpRegistration()))
    response = client.post(
        "/internal/v1/indexing",
        headers={"X-Sage-Timestamp": str(int(time.time())), "X-Sage-Signature": "wrong"},
        json={
            "taskId": "task-1",
            "attempt": 1,
            "knowledgeBaseId": 7,
            "documentId": "doc-1",
            "filename": "test.txt",
            "sourceUrl": "http://minio/test.txt",
            "requestId": "req-1",
        },
    )
    assert response.status_code == 401


def test_signed_indexing_command_cannot_be_replayed() -> None:
    client = TestClient(create_app(settings_for_test(), registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    payload = {
        "taskId": "task-1",
        "attempt": 1,
        "knowledgeBaseId": 7,
        "documentId": "doc-1",
        "filename": "test.txt",
        "sourceUrl": "http://minio/test.txt",
        "requestId": "req-1",
    }
    signature = _sign(payload, timestamp)

    assert client.post("/internal/v1/indexing", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 202
    assert client.post("/internal/v1/indexing", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 401
