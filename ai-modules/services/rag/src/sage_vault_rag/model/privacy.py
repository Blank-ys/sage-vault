"""隐私与诊断脱敏工具。

网关/浏览器只应看到经过脱敏的失败类别；原始异常文本、模型与 trace 标识、
知识库 id 等诊断信息必须留在服务端日志，不得进入 SSE 流或任何对外响应。
"""

from __future__ import annotations

import re

# 服务侧诊断日志使用的标记，便于跨语言（Java / Python）关联同一次失败。
TRACE_PREFIX = "trace="

# 对外可见的失败类别。必须是受控词表，绝不能包含具体诊断内容。
FAILURE_CATEGORY_GENERATION = "retrieval_or_generation_failed"
FAILURE_CATEGORY_EMBEDDING = "embedding_failed"
FAILURE_CATEGORY_VECTOR_STORE = "vector_store_failed"
FAILURE_CATEGORY_UNKNOWN = "unexpected_failure"

# 这些模式一旦出现在日志/异常里就是敏感信息，落盘或对外前必须抹掉。
_SENSITIVE_PATTERNS = (
    re.compile(r"sk-[A-Za-z0-9]{8,}"),  # DashScope / OpenAI 风格 api key
    re.compile(r"Bearer\s+[A-Za-z0-9._-]+", re.IGNORECASE),  # 访问令牌
    re.compile(r"password\s*=\s*\S+", re.IGNORECASE),  # 连接串里的密码
)


def mask_sensitive(value: str) -> str:
    """抹掉字符串里的密钥、令牌或密码，供日志落盘前使用。"""
    masked = value
    for pattern in _SENSITIVE_PATTERNS:
        masked = pattern.sub("[REDACTED]", masked)
    return masked


def classify_failure(error: BaseException) -> str:
    """把任意运行时异常归约到受控的失败类别词表（对外安全）。"""
    module = type(error).__module__
    name = type(error).__qualname__
    fqn = f"{module}.{name}" if module else name
    lowered = fqn.lower() + " " + str(error).lower()
    if "embedding" in lowered or "bge" in lowered:
        return FAILURE_CATEGORY_EMBEDDING
    if "milvus" in lowered or "vector" in lowered or "grpc" in lowered:
        return FAILURE_CATEGORY_VECTOR_STORE
    if "generat" in lowered or "dashscope" in lowered or "bailian" in lowered or "llm" in lowered:
        return FAILURE_CATEGORY_GENERATION
    return FAILURE_CATEGORY_UNKNOWN


def mask_failure_detail(error: BaseException) -> str:
    """生成对外 SSE 事件里携带的、已脱敏的失败文案。"""
    return classify_failure(error)
