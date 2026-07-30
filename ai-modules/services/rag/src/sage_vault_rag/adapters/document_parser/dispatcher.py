from collections.abc import Mapping

from sage_vault_rag.model.parsed_document import ParsedDocument
from sage_vault_rag.ports.document_parser import DocumentParserPort


class FormatDispatchingDocumentParser:
    """按文件扩展名分发到具体解析器。

    IndexingService 只依赖 `DocumentParserPort`；本类是装配层 adapter，
    把 TXT/MD/PDF/DOCX 等具体解析器按扩展名注册并按 `filename` 派发。
    未注册的扩展名抛出 `ValueError`，由 IndexingService 统一走失败清理与回调。
    """

    def __init__(self, parsers: Mapping[str, DocumentParserPort]) -> None:
        if not parsers:
            raise ValueError("必须注册至少一种文档解析器")
        self._parsers: dict[str, DocumentParserPort] = dict(parsers)

    async def parse(self, content: bytes, filename: str) -> ParsedDocument:
        extension = self._extract_extension(filename)
        parser = self._parsers.get(extension)
        if parser is None:
            raise ValueError(f"不支持的文档格式: {filename}")
        return await parser.parse(content, filename)

    @staticmethod
    def _extract_extension(filename: str) -> str:
        last_dot = filename.rfind(".")
        if last_dot <= 0 or last_dot == len(filename) - 1:
            raise ValueError(f"不支持的文档格式: {filename}")
        return filename[last_dot + 1 :].lower()
