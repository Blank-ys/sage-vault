import hashlib
import hmac
import json
import time
from collections.abc import AsyncIterator
from pathlib import Path

from fastapi.testclient import TestClient

from sage_vault_rag.application.answering.cancellation import CancellationRegistry
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.model.events import AnswerEvent, Completed, Delta, Refused, Started, Stopped
from sage_vault_rag.transport.http.app import create_app


class NoOpRegistration:
    async def register(self) -> None:
        pass

    async def close(self) -> None:
        pass


class FakeAnsweringService(AnsweringService):
    def __init__(self, events: list[AnswerEvent]) -> None:
        self._events = events
        self._cancellations = CancellationRegistry()

    async def answer(
        self,
        knowledge_base_id: int,
        question: str,
        generation_id: str,
    ) -> AsyncIterator[AnswerEvent]:
        with self._cancellations.track(generation_id):
            for event in self._events:
                yield event


def settings_for_test() -> Settings:
    return Settings(
        signing_key="test-key",
        nacos_server_address="nacos.test:8848",
        embedding_model_path="/dev/null/model",
    )


def _sign_answer_request(request: dict[str, str], timestamp: str, key: str = "test-key") -> str:
    question_hash = hashlib.sha256(request["question"].encode()).hexdigest()
    value = (
        f"{request['knowledgeBaseId']}:{request['requestId']}:{request['generationId']}:"
        f"{timestamp}:{question_hash}"
    ).encode()
    return hmac.new(key.encode(), value, hashlib.sha256).hexdigest()


def test_empty_knowledge_base_streams_started_then_refused() -> None:
    contract_root = Path(__file__).parents[5] / "contracts" / "java-python-rag" / "v1"
    example = json.loads((contract_root / "examples" / "empty-knowledge-base.json").read_text(encoding="utf-8"))
    request: dict[str, str] = {key: str(value) for key, value in example["request"].items()}
    generation_id = request["generationId"]
    answering = FakeAnsweringService([
        Started(generation_id),
        Refused(generation_id, "该知识库暂无可用文档"),
    ])
    client = TestClient(create_app(settings_for_test(), answering=answering, registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    signature = _sign_answer_request(request, timestamp)

    response = client.post(
        "/internal/v1/answers",
        headers={"X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature},
        json=request,
    )

    assert response.status_code == 200
    for event in example["events"]:
        assert f"event: {event['type']}" in response.text
        assert json.dumps(event, ensure_ascii=False, separators=(",", ":")) in response.text.replace(" ", "")


def test_successful_answer_streams_started_delta_completed() -> None:
    request: dict[str, str] = {
        "knowledgeBaseId": "1",
        "question": "公司制度是什么？",
        "requestId": "req-1",
        "generationId": "8bcdd88e-9e64-4cd1-b781-f9a890f691a6",
    }
    generation_id = request["generationId"]
    answering = FakeAnsweringService([
        Started(generation_id),
        Delta(generation_id, "根据"),
        Delta(generation_id, "文档"),
        Completed(generation_id),
    ])
    client = TestClient(create_app(settings_for_test(), answering=answering, registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    signature = _sign_answer_request(request, timestamp)

    response = client.post(
        "/internal/v1/answers",
        headers={"X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature},
        json=request,
    )

    assert response.status_code == 200
    assert "event: started" in response.text
    assert "event: delta" in response.text
    assert "event: completed" in response.text
    assert json.dumps({"type": "delta", "generationId": generation_id, "delta": "根据"}, ensure_ascii=False) in response.text


def _sign_cancel_request(generation_id: str, request_id: str, timestamp: str, key: str = "test-key") -> str:
    value = f"cancel:{generation_id}:{request_id}:{timestamp}".encode()
    return hmac.new(key.encode(), value, hashlib.sha256).hexdigest()


def test_stopped_event_is_streamed_with_contract_payload() -> None:
    contract_root = Path(__file__).parents[5] / "contracts" / "java-python-rag" / "v1"
    example = json.loads((contract_root / "examples" / "stopped-generation.json").read_text(encoding="utf-8"))
    generation_id = example["request"]["generationId"]
    request: dict[str, str] = {
        "knowledgeBaseId": "1",
        "question": "公司制度是什么？",
        "requestId": "req-1",
        "generationId": generation_id,
    }
    answering = FakeAnsweringService([
        Started(generation_id),
        Delta(generation_id, "Sage Vault 的检索"),
        Stopped(generation_id),
    ])
    client = TestClient(create_app(settings_for_test(), answering=answering, registration=NoOpRegistration()))
    timestamp = str(int(time.time()))

    response = client.post(
        "/internal/v1/answers",
        headers={"X-Sage-Timestamp": timestamp, "X-Sage-Signature": _sign_answer_request(request, timestamp)},
        json=request,
    )

    assert response.status_code == 200
    streamed = [
        (block.splitlines()[0].removeprefix("event: "), json.loads(block.splitlines()[1].removeprefix("data: ")))
        for block in response.text.strip().split("\n\n")
    ]
    assert streamed == [(event["type"], event) for event in example["events"]]


def test_cancel_acknowledges_generation_owned_by_this_instance() -> None:
    generation_id = "0f6f6b1e-6d1a-4f5f-9a0f-2b3c4d5e6f70"
    answering = FakeAnsweringService([Started(generation_id)])
    app = create_app(settings_for_test(), answering=answering, registration=NoOpRegistration())
    client = TestClient(app)
    timestamp = str(int(time.time()))

    with answering.cancellations.track(generation_id) as cancelled:
        response = client.post(
            f"/internal/v1/answers/{generation_id}/cancel",
            headers={
                "X-Sage-Timestamp": timestamp,
                "X-Sage-Signature": _sign_cancel_request(generation_id, "req-stop-1", timestamp),
            },
            json={"generationId": generation_id, "requestId": "req-stop-1"},
        )

        assert response.status_code == 202
        assert response.json() == {"generationId": generation_id, "cancelled": True}
        assert cancelled.is_set()


def test_cancel_for_unknown_generation_is_acknowledged_as_not_cancelled() -> None:
    generation_id = "0f6f6b1e-6d1a-4f5f-9a0f-2b3c4d5e6f70"
    client = TestClient(create_app(settings_for_test(), registration=NoOpRegistration()))
    timestamp = str(int(time.time()))

    response = client.post(
        f"/internal/v1/answers/{generation_id}/cancel",
        headers={
            "X-Sage-Timestamp": timestamp,
            "X-Sage-Signature": _sign_cancel_request(generation_id, "req-stop-2", timestamp),
        },
        json={"generationId": generation_id, "requestId": "req-stop-2"},
    )

    assert response.status_code == 202
    assert response.json() == {"generationId": generation_id, "cancelled": False}


def test_unsigned_cancel_request_is_rejected() -> None:
    generation_id = "0f6f6b1e-6d1a-4f5f-9a0f-2b3c4d5e6f70"
    client = TestClient(create_app(settings_for_test(), registration=NoOpRegistration()))

    response = client.post(
        f"/internal/v1/answers/{generation_id}/cancel",
        headers={"X-Sage-Timestamp": str(int(time.time())), "X-Sage-Signature": "wrong"},
        json={"generationId": generation_id, "requestId": "req-stop-3"},
    )

    assert response.status_code == 401


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
    generation_id = "8bcdd88e-9e64-4cd1-b781-f9a890f691a6"
    answering = FakeAnsweringService([Started(generation_id), Refused(generation_id, "该知识库暂无可用文档")])
    client = TestClient(create_app(settings_for_test(), answering=answering, registration=NoOpRegistration()))
    timestamp = str(int(time.time()))
    question_hash = hashlib.sha256("问题".encode()).hexdigest()
    value = f"1:req-1:{generation_id}:{timestamp}:{question_hash}".encode()
    signature = hmac.new(b"test-key", value, hashlib.sha256).hexdigest()
    payload = {
        "knowledgeBaseId": 1,
        "question": "问题",
        "requestId": "req-1",
        "generationId": generation_id,
    }

    assert client.post("/internal/v1/answers", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 200
    assert client.post("/internal/v1/answers", headers={
        "X-Sage-Timestamp": timestamp, "X-Sage-Signature": signature}, json=payload).status_code == 401
