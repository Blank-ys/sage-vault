from dataclasses import dataclass


@dataclass(frozen=True)
class ParsedParagraph:
    """解析得到的自然段，携带可选的来源标题或页码元数据。

    `heading` 表示该段落当前所属的章节/标题（来自 Markdown ATX 标题或 PDF/DOCX 章节信息）；
    `page_number` 表示该段落在 PDF 中所属的页码（从 1 开始）。MD/TXT 解析器始终为 `None`。
    """

    text: str
    heading: str | None = None
    page_number: int | None = None


@dataclass(frozen=True)
class ParsedDocument:
    """结构化文档：由按文档顺序排列的自然段组成。

    IndexingService 把 `ParsedDocument` 传给 ChunkerPort；ChunkerPort 据此保留标题与自然段边界，
    并允许后续工单把 `heading`/`page_number` 写入片段元数据。
    """

    paragraphs: list[ParsedParagraph]
