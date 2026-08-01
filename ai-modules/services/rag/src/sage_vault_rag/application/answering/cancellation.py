import asyncio
from collections.abc import Iterator
from contextlib import contextmanager


class CancellationRegistry:
    """进程内在途生成登记表，用于把 Java 的取消命令投递给拥有该生成的流。

    该登记表只承载"尽力而为"的取消信号，不保存任何业务状态：终态由 Java 裁决。
    取消命令必须被路由回持有该生成的实例，未命中时返回 False 而不是报错。
    """

    def __init__(self) -> None:
        self._signals: dict[str, asyncio.Event] = {}

    @contextmanager
    def track(self, generation_id: str) -> Iterator[asyncio.Event]:
        signal = asyncio.Event()
        self._signals[generation_id] = signal
        try:
            yield signal
        finally:
            self._signals.pop(generation_id, None)

    def cancel(self, generation_id: str) -> bool:
        signal = self._signals.get(generation_id)
        if signal is None:
            return False
        signal.set()
        return True

    def is_tracked(self, generation_id: str) -> bool:
        return generation_id in self._signals
