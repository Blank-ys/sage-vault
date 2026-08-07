"""AnswerEvent -> SSE wire frame 映射。

对外 SSE payload 与根契约的事件 schema 一一对应：只暴露项目自有字段，
不携带原始异常、trace 标识或知识库 id。
"""

import json

from sage_vault_rag.model.events import AnswerEvent, Completed, Delta, Failed, Refused, Started, Stopped


def render_sse(event: AnswerEvent) -> str:
    """把一个回答执行事件渲染为单个 SSE frame。"""
    payload: dict[str, object]
    if isinstance(event, Started):
        payload = {"type": "started", "generationId": event.generation_id}
    elif isinstance(event, Delta):
        payload = {"type": "delta", "generationId": event.generation_id, "delta": event.delta}
    elif isinstance(event, Completed):
        payload = {
            "type": "completed",
            "generationId": event.generation_id,
            "retrievalDiagnostics": [
                {"documentId": diag.document_id, "chunkId": diag.chunk_id, "score": diag.score}
                for diag in event.retrieval_diagnostics
            ],
            "stageDurations": event.stage_durations,
            "modelRequestId": event.model_request_id,
        }
    elif isinstance(event, Refused):
        payload = {"type": "refused", "generationId": event.generation_id, "message": event.message}
    elif isinstance(event, Failed):
        # detail 已是脱敏后的受控失败类别，不含原始异常/密钥/知识库 id。
        payload = {"type": "failed", "generationId": event.generation_id, "detail": event.detail}
    elif isinstance(event, Stopped):
        payload = {"type": "stopped", "generationId": event.generation_id}
    else:
        raise TypeError(f"unknown answer event: {event!r}")
    return f"event: {payload['type']}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"
