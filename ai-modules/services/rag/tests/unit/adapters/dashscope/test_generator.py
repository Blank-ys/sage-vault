from collections.abc import Iterable
from typing import Any

import pytest

from sage_vault_rag.adapters.dashscope.generator import DashScopeGenerationAdapter
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk


class _FakeMessage:
    def __init__(self, content: str) -> None:
        self.content = content


class _FakeChoice:
    def __init__(self, content: str) -> None:
        self.message = _FakeMessage(content)


class _FakeOutput:
    def __init__(self, content: str) -> None:
        self.choices = [_FakeChoice(content)]


class _FakeResponse:
    def __init__(self, content: str, status_code: int = 200, request_id: str = "req-1") -> None:
        self.output = _FakeOutput(content)
        self.status_code = status_code
        self.request_id = request_id


class _CapturingStreamCall:
    def __init__(self, responses: list[Any]) -> None:
        self._responses = list(responses)
        self.captured: dict[str, Any] = {}

    def __call__(self, **kwargs: Any) -> Iterable[Any]:
        self.captured.update(kwargs)
        return iter(self._responses)


def _chunk(text: str) -> RetrievedChunk:
    return RetrievedChunk("c1", "d1", "file.txt", 0, text, score=0.3)


async def test_streams_deltas_from_bailian_chunks() -> None:
    stream_call = _CapturingStreamCall([
        _FakeResponse("根据"),
        _FakeResponse("文档"),
        _FakeResponse(""),
    ])
    adapter = DashScopeGenerationAdapter(
        api_key="sk-test",
        model="qwen-plus",
        stream_call=stream_call,
    )

    deltas = [delta async for delta in adapter.generate("gen-1", "问题", [_chunk("答案内容")])]

    assert deltas == ["根据", "文档"]
    assert stream_call.captured["model"] == "qwen-plus"
    assert stream_call.captured["api_key"] == "sk-test"
    assert stream_call.captured["stream"] is True
    assert stream_call.captured["incremental_output"] is True
    assert stream_call.captured["result_format"] == "message"


async def test_prompt_only_includes_retrieved_chunks_and_question() -> None:
    stream_call = _CapturingStreamCall([_FakeResponse("ok")])
    adapter = DashScopeGenerationAdapter(api_key="sk-test", stream_call=stream_call)

    [_ async for _ in adapter.generate("gen-1", "员工福利有哪些？", [_chunk("带薪年假")])]

    messages = stream_call.captured["messages"]
    assert messages[0]["role"] == "system"
    assert "不得使用模型自身的通用知识" in messages[0]["content"]
    assert "简洁中文" in messages[0]["content"]
    user_content = messages[1]["content"]
    assert "带薪年假" in user_content
    assert "员工福利有哪些？" in user_content


async def test_upstream_error_status_raises_and_breaks_stream() -> None:
    stream_call = _CapturingStreamCall([_FakeResponse("部分", status_code=500)])
    adapter = DashScopeGenerationAdapter(api_key="sk-test", stream_call=stream_call)

    with pytest.raises(RuntimeError):
        [_ async for _ in adapter.generate("gen-1", "问题", [_chunk("内容")])]


async def test_mid_stream_exception_propagates_and_keeps_prior_deltas() -> None:
    def stream_call(**kwargs: Any) -> Iterable[Any]:
        yield _FakeResponse("第一段")
        raise ConnectionError("upstream dropped")

    adapter = DashScopeGenerationAdapter(api_key="sk-test", stream_call=stream_call)

    deltas: list[str] = []
    with pytest.raises(ConnectionError):
        async for delta in adapter.generate("gen-1", "问题", [_chunk("内容")]):
            deltas.append(delta)

    assert deltas == ["第一段"]


def test_empty_api_key_rejected() -> None:
    with pytest.raises(ValueError):
        DashScopeGenerationAdapter(api_key="")


def test_empty_model_rejected() -> None:
    with pytest.raises(ValueError):
        DashScopeGenerationAdapter(api_key="sk-test", model="")


async def test_empty_chunks_still_builds_prompt() -> None:
    stream_call = _CapturingStreamCall([_FakeResponse("根据现有文档无法回答")])
    adapter = DashScopeGenerationAdapter(api_key="sk-test", stream_call=stream_call)

    deltas = [delta async for delta in adapter.generate("gen-1", "问题", [])]

    assert deltas == ["根据现有文档无法回答"]
    user_content = stream_call.captured["messages"][1]["content"]
    assert "文档片段：" in user_content
    assert "问题：问题" in user_content


async def test_default_max_tokens_and_temperature_forwarded() -> None:
    stream_call = _CapturingStreamCall([_FakeResponse("ok")])
    adapter = DashScopeGenerationAdapter(api_key="sk-test", stream_call=stream_call)

    [_ async for _ in adapter.generate("gen-1", "问题", [_chunk("内容")])]

    assert stream_call.captured["max_tokens"] == 1024
    assert stream_call.captured["temperature"] == 0.3
    assert stream_call.captured["request_timeout"] == 60.0
