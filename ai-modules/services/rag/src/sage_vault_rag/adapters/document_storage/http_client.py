import httpx


class HttpDocumentStorage:
    """通过 Java 下发的限时 URL 下载原文件。"""

    def __init__(self, timeout_seconds: float = 60.0) -> None:
        self._timeout = timeout_seconds

    async def download(self, source_url: str) -> bytes:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.get(source_url)
            response.raise_for_status()
            return response.content
