import re

from charset_normalizer import detect

from sage_vault_rag.model.parsed_document import ParsedDocument, ParsedParagraph

_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")


class TxtParser:
    """TXT 解析器，自动探测编码并返回按自然段切分的结构化文档。

    保留 02 工单的既有契约：空内容返回空 `ParsedDocument`（不抛异常），
    编码不可靠时抛出 `ValueError`。本解析器不识别标题或页码。
    """

    async def parse(self, content: bytes, filename: str) -> ParsedDocument:
        if not content:
            return ParsedDocument(paragraphs=[])
        detected = detect(content)
        encoding = detected.get("encoding") or "utf-8"
        confidence = detected.get("confidence") or 0.0
        decode_error: Exception | None = None
        try:
            text = content.decode(encoding, errors="strict")
        except UnicodeDecodeError as exception:
            decode_error = exception
            text = content.decode("utf-8", errors="replace")
        normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        if confidence < 0.5 and "\ufffd" in normalized:
            message = f"无法可靠解析 TXT 文件编码: {filename}"
            if decode_error is not None:
                raise ValueError(message) from decode_error
            raise ValueError(message)
        paragraphs = [
            ParsedParagraph(text=paragraph.strip())
            for paragraph in _PARAGRAPH_SPLIT.split(normalized)
            if paragraph.strip()
        ]
        return ParsedDocument(paragraphs=paragraphs)
