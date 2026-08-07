"""生成生命周期阶段：流式输出 delta、响应取消、失败上报与生成器关闭。

停止、拒答、失败和 generator 关闭的规则集中在这里；对外只 yield
Delta / Stopped / Failed 事件，终态仍由 Java 裁决。
"""

import asyncio
import time
from collections.abc import AsyncGenerator

from sage_vault_rag.application.answering.diagnostics import AnswerDiagnostics
from sage_vault_rag.application.answering.timing import close_quietly, ms_since
from sage_vault_rag.model.events import Delta, Failed, Stopped
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk
from sage_vault_rag.ports.generation import GenerationPort


class GenerationLifecycle:
    """驱动生成器迭代，把原始 delta 流转换为受控的回答事件流。"""

    def __init__(self, generator: GenerationPort) -> None:
        self._generator = generator

    async def stream(
        self,
        generation_id: str,
        question: str,
        chunks: list[RetrievedChunk],
        cancelled: asyncio.Event,
        durations: dict[str, int],
        diagnostics: AnswerDiagnostics,
    ) -> AsyncGenerator[Delta | Stopped | Failed, None]:
        deltas = self._generator.generate(generation_id, question, chunks)
        generation_start = time.perf_counter()
        try:
            async for delta in deltas:
                if cancelled.is_set():
                    yield Stopped(generation_id)
                    return
                yield Delta(generation_id, delta)
                if cancelled.is_set():
                    yield Stopped(generation_id)
                    return
        except Exception as error:  # noqa: BLE001 适配器边界：任何生成器异常都映射为 Failed
            # 生成流在已产出部分 delta 后崩溃：已产出的 delta 依然有效，
            # 但流程必须以 Failed 终止，不能留下空白或误导性的完成卡片。
            yield Failed(generation_id, diagnostics.failure("generate", error))
            return
        finally:
            durations["generation"] = ms_since(generation_start)
            await close_quietly(deltas)
