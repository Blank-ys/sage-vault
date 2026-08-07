"""回答执行阶段的计时与资源关闭工具。"""

import asyncio
import time
from collections.abc import AsyncIterator


def ms_since(start: float) -> int:
    """perf_counter 起点到现在的毫秒耗时，向上取整为整数毫秒。"""
    return max(0, int((time.perf_counter() - start) * 1000))


async def close_quietly(deltas: AsyncIterator[str]) -> None:
    aclose = getattr(deltas, "aclose", None)
    if aclose is None:
        return
    try:
        await aclose()
    except (asyncio.CancelledError, RuntimeError, StopAsyncIteration):
        return
