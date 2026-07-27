import hashlib
import hmac
import json
import time
from pathlib import Path

from fastapi.testclient import TestClient

from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.transport.http.app import create_app


class NoOpRegistration:
    async def register(self) -> None:
        pass

    async def close(self) -> None:
        pass


def settings_for_test() -> Settings:
    return Settings(
        signing_key="test-key",
        nacos_server_address="nacos.test:8848",
        embedding_model_path="/dev/null/model",
    )


def test_empty_knowledge_base_streams_started_then_refused() -> None:
    contract_root = Path(__file__).parents[5] / "contracts" / "java-python-rag" / "v1"
    example = json.loads((contract_root / "examples" / "empty-knowledge-base.json").read_text(encoding="utf-8"))
    client = TestClient(create_app(settings_for_test(), registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    request = example["request"]
    question_hash = hashlib.sha256(request["question"].encode()).hexdigest()
    value = (
        f"{request['knowledgeBaseId']}:{request['requestId']}:{request['generationId']}:{timestamp}:{question_hash}"
    ).encode()
    signature = hmac.new(b"test-key", value, hashlib.sha256).hexdigest()

    response = client.post(
        "/internal/v1/answers",
        headers={"X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature},
        json=request,
    )

    assert response.status_code == 200
    for event in example["events"]:
        assert f"event: {event['type']}" in response.text
        assert json.dumps(event, ensure_ascii=False, separators=(",", ":")) in response.text.replace(" ", "")


def test_unsigned_request_is_rejected() -> None:
    client = TestClient(create_app(settings_for_test(), registration=NoOpRegistration()))
    response = client.post(
        "/internal/v1/answers",
        headers={"X-Sage-Timestamp": str(int(time.time())), "X-Sage-Signature": "wrong"},
        json={
            "knowledgeBaseId": 1,
            "question": "问题",
            "requestId": "req-1",
            "generationId": "8bcdd88e-9e64-4cd1-b781-f9a890f691a6",
        },
    )
    assert response.status_code == 401


def test_signed_request_cannot_be_replayed() -> None:
    client = TestClient(create_app(settings_for_test(), registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    question_hash = hashlib.sha256("问题".encode()).hexdigest()
    value = f"1:req-1:8bcdd88e-9e64-4cd1-b781-f9a890f691a6:{timestamp}:{question_hash}".encode()
    signature = hmac.new(b"test-key", value, hashlib.sha256).hexdigest()
    payload = {
        "knowledgeBaseId": 1,
        "question": "问题",
        "requestId": "req-1",
        "generationId": "8bcdd88e-9e64-4cd1-b781-f9a890f691a6",
    }

    assert client.post("/internal/v1/answers", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 200
    assert client.post("/internal/v1/answers", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 401
