import pytest

from sage_vault_rag.model.privacy import (
    FAILURE_CATEGORY_EMBEDDING,
    FAILURE_CATEGORY_GENERATION,
    FAILURE_CATEGORY_VECTOR_STORE,
    classify_failure,
    mask_failure_detail,
    mask_sensitive,
)


def test_mask_sensitive_redacts_api_key_and_token_and_password() -> None:
    raw = "call DashScope sk-ABC123DEF456 with Bearer eyJhbGci.token and password=secret123"
    masked = mask_sensitive(raw)
    assert "sk-ABC123DEF456" not in masked
    assert "eyJhbGci" not in masked
    assert "secret123" not in masked
    assert "[REDACTED]" in masked


def test_classify_embedding_error_stays_user_safe() -> None:
    error = RuntimeError("bge-m3 embedding backend returned 503")
    assert classify_failure(error) == FAILURE_CATEGORY_EMBEDDING
    # 原始异常文本不得成为对外文案。
    assert "503" not in mask_failure_detail(error)


def test_classify_vector_store_error() -> None:
    error = ConnectionError("Milvus grpc channel closed unexpectedly")
    assert classify_failure(error) == FAILURE_CATEGORY_VECTOR_STORE


def test_classify_generation_error() -> None:
    error = TimeoutError("dashscope qwen-plus generation timed out")
    assert classify_failure(error) == FAILURE_CATEGORY_GENERATION


def test_classify_unknown_error_returns_controlled_category() -> None:
    error = ValueError("some opaque internal reason knowledge_base_id=7 leaked")
    detail = mask_failure_detail(error)
    assert detail == "unexpected_failure"
    # 即便原始异常里夹带了知识库 id，对外文案也不应包含它。
    assert "knowledge_base_id=7" not in detail


@pytest.mark.parametrize(
    "error",
    [
        RuntimeError("bge-m3 embedding backend returned 503"),
        ConnectionError("Milvus grpc channel closed unexpectedly"),
        TimeoutError("dashscope qwen-plus generation timed out"),
        ValueError("opaque"),
    ],
)
def test_mask_failure_detail_always_in_controlled_vocabulary(error: BaseException) -> None:
    assert mask_failure_detail(error) in {
        FAILURE_CATEGORY_EMBEDDING,
        FAILURE_CATEGORY_VECTOR_STORE,
        FAILURE_CATEGORY_GENERATION,
        "unexpected_failure",
    }
