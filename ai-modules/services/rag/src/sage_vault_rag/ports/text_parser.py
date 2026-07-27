from typing import Protocol


class TextParserPort(Protocol):
    """将原文件字节解析为纯文本。"""

    async def parse(self, content: bytes, filename: str) -> str:
        """返回文档正文；无法解析时抛出已注册的业务异常。"""
        ...
