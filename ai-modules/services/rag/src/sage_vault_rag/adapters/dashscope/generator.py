import asyncio
import logging
from collections.abc import AsyncIterator, Callable, Iterable, Iterator
from typing import Any, cast

from sage_vault_rag.model.retrieved_chunk import RetrievedChunk
from sage_vault_rag.ports.generation import GenerationPort

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "你是企业知识库问答助手。请仅根据下方提供的文档片段回答问题，"
    "不得使用模型自身的通用知识补答。回答使用简洁中文，结论优先。"
    "若文档片段不足以回答问题，直接回复：根据现有文档无法回答。"
)


class DashScopeGenerationAdapter(GenerationPort):
    """百炼（含 MaaS 私有实例）流式生成适配器：把 OpenAI 兼容流转换为项目自有的 delta 流。

    通过 openai 客户端访问百炼暴露的 OpenAI 兼容 /chat/completions 接口，
    DashScope/openai 凭据与类型仅在本适配器内出现，不进入 port 或 application；
    上游错误或中途断流以异常方式向上抛出，由既有问答流程保存残缺内容。
    """

    def __init__(
        self,
        api_key: str,
        model: str = "qwen-plus",
        max_tokens: int = 1024,
        temperature: float = 0.3,
        timeout: float = 60.0,
        base_url: str = "",
        stream_call: Callable[..., Iterable[Any]] | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("bailian api key 不能为空")
        if not model:
            raise ValueError("bailian model 不能为空")
        self._api_key = api_key
        self._model = model
        self._max_tokens = max_tokens
        self._temperature = temperature
        self._timeout = timeout
        self._base_url = base_url
        self._stream_call: Callable[..., Iterable[Any]] = stream_call or _default_stream_call

    async def generate(
        self,
        generation_id: str,
        question: str,
        chunks: list[RetrievedChunk],
    ) -> AsyncIterator[str]:
        messages = self._build_messages(question, chunks)
        stream = self._open_stream(messages)
        iterator = iter(stream)
        while True:
            try:
                response = await asyncio.to_thread(_next_or_none, iterator)
            except Exception as exception:
                logger.warning(
                    "百炼流式中断: error_type=%s model=%s",
                    type(exception).__name__,
                    self._model,
                )
                raise
            # 仅调试使用
            # print("百炼调用返回结果: response=", response, " model=", self._model)
            if response is None:
                return
            self._raise_if_error(response)
            delta = self._extract_delta(response)
            if delta:
                yield delta

    def _open_stream(self, messages: list[dict[str, str]]) -> Iterable[Any]:
        kwargs: dict[str, Any] = {
            "api_key": self._api_key,
            "model": self._model,
            "messages": messages,
            "stream": True,
            "max_tokens": self._max_tokens,
            "temperature": self._temperature,
            "timeout": self._timeout,
        }
        if self._base_url:
            kwargs["base_url"] = self._base_url
        try:
            return self._stream_call(**kwargs)
        except Exception as exception:
            logger.warning(
                "百炼流式调用发起失败: error_type=%s model=%s",
                type(exception).__name__,
                self._model,
            )
            raise

    def _raise_if_error(self, response: Any) -> None:
        status_code = getattr(response, "status_code", 200)
        if status_code == 200:
            return
        request_id = getattr(response, "request_id", "") or ""
        logger.warning(
            "百炼返回错误状态: status=%s request_id=%s model=%s",
            status_code,
            request_id,
            self._model,
        )
        raise RuntimeError("百炼流式生成失败")

    def _extract_delta(self, response: Any) -> str | None:
        # OpenAI 兼容 chunk：response.choices[0].delta.content
        if hasattr(response, "choices"):
            choices = response.choices or []
        else:
            output = getattr(response, "output", None)
            if output is None:
                return None
            choices = getattr(output, "choices", None) or []
        if not choices:
            return None
        delta = getattr(choices[0], "delta", None)
        if delta is None:
            message = getattr(choices[0], "message", None)
            content = getattr(message, "content", None) if message is not None else None
        else:
            content = getattr(delta, "content", None)
        if not content:
            return None
        return str(content)

    def _build_messages(self, question: str, chunks: list[RetrievedChunk]) -> list[dict[str, str]]:
        evidence = "\n\n".join(
            f"【片段{i + 1}】（来源：{chunk.filename}）\n{chunk.text}"
            for i, chunk in enumerate(chunks)
        )
        user_content = f"文档片段：\n{evidence}\n\n问题：{question}"
        return [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ]


def _default_stream_call(**kwargs: Any) -> Iterable[Any]:
    from openai import OpenAI  # 仅在适配器内延迟导入，凭据与类型不离开本模块

    client = OpenAI(
        api_key=kwargs.pop("api_key"),
        base_url=kwargs.pop("base_url", None) or None,
        timeout=kwargs.get("timeout", 60.0),
        max_retries=0,
    )
    responses = client.chat.completions.create(**kwargs)
    return cast(Iterable[Any], responses)


def _next_or_none(iterator: Iterator[Any]) -> Any:
    try:
        return next(iterator)
    except StopIteration:
        return None

