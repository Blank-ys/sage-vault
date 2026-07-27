from typing import Protocol


class DocumentStoragePort(Protocol):
    """从限时源地址下载企业文档原文件。"""

    async def download(self, source_url: str) -> bytes:
        """返回原文件字节流。"""
        ...
