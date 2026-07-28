import re

from charset_normalizer import detect

from sage_vault_rag.model.parsed_document import ParsedDocument, ParsedParagraph

_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")
_ATX_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
# Markdown 文档可能以 UTF-8 with BOM 开头，去掉后再解析。
_BOM = "\ufeff"


class MarkdownParser:
    """Markdown 解析器，识别 ATX 标题并保留标题/自然段边界。

    - 每个独立 ATX 标题行（`#` ~ `######`）作为单独的 `ParsedParagraph` 输出，
      `text` 保留完整 ATX 语法（如 `# 知识库管理办法`），`heading` 仅保留标题文本
      （如 `知识库管理办法`），便于 03e 直接写入 `Chunk.section_title`。
    - 自然段按空行切分；超长自然段不由解析器切分，交给 ChunkerPort 处理。
    - 编码不可靠（低置信度且出现替换字符）时抛 `ValueError`，与 TxtParser 行为一致。
    - 空文档或仅含空白/不可提取文本的文档抛出 `ValueError`，附带文件名。
    - 本解析器不解析页码（PDF 才有，由 03c 引入）。
    """

    async def parse(self, content: bytes, filename: str) -> ParsedDocument:
        if not content:
            raise ValueError(f"无法从 Markdown 文件中提取任何文本内容: {filename}")
        detected = detect(content)
        encoding = detected.get("encoding") or "utf-8"
        confidence = detected.get("confidence") or 0.0
        try:
            text = content.decode(encoding, errors="strict")
        except UnicodeDecodeError:
            text = content.decode("utf-8", errors="replace")
        normalized = text.replace("\r\n", "\n").replace("\r", "\n").removeprefix(_BOM)
        if confidence < 0.5 and "\ufffd" in normalized:
            raise ValueError(f"无法可靠解析 Markdown 文件编码: {filename}")
        paragraphs: list[ParsedParagraph] = []
        current_heading: str | None = None
        for block in _PARAGRAPH_SPLIT.split(normalized):
            block = block.strip()
            if not block:
                continue
            if "\n" not in block:
                heading_match = _ATX_HEADING.match(block)
                if heading_match:
                    # text 保留完整 ATX 语法，heading 仅保留标题文本（去掉 # 前缀）
                    current_heading = heading_match.group(2).strip()
                    paragraphs.append(ParsedParagraph(text=block, heading=current_heading))
                    continue
            paragraphs.append(ParsedParagraph(text=block, heading=current_heading))
        if not paragraphs:
            raise ValueError(f"无法从 Markdown 文件中提取任何文本内容: {filename}")
        return ParsedDocument(paragraphs=paragraphs)
