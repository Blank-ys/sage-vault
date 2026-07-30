import io
import re

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from sage_vault_rag.model.parsed_document import ParsedDocument, ParsedParagraph

_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")


class PdfParser:
    """PDF 解析器，按页提取文本并记录页码元数据。

    失败语义（与 03c 工单对齐）：
    - 加密 PDF（且空密码无法解密）抛 ``ValueError("文件已加密，无法解析: <filename>")``。
    - 损坏或无法识别的 PDF 抛 ``ValueError("文件损坏，无法解析: <filename>")``。
    - 空白或扫描版 PDF（无法提取任何文本）抛 ``ValueError("未检测到可提取文本: <filename>")``。

    成功解析时按页提取文本，按双换行切分自然段；每个段落携带 ``page_number``
    元数据（从 1 开始），供 03e 工单写入 ``Chunk.page_number``。`heading` 始终为
    ``None``（PDF 文本层无章节结构信息，不强行猜测）。
    """

    async def parse(self, content: bytes, filename: str) -> ParsedDocument:
        if not content:
            raise ValueError(f"未检测到可提取文本: {filename}")
        reader = self._create_reader(content, filename)
        if reader.is_encrypted:
            self._try_decrypt(reader, filename)
        paragraphs = self._extract_paragraphs(reader, filename)
        if not paragraphs:
            raise ValueError(f"未检测到可提取文本: {filename}")
        return ParsedDocument(paragraphs=paragraphs)

    @staticmethod
    def _create_reader(content: bytes, filename: str) -> PdfReader:
        try:
            return PdfReader(io.BytesIO(content))
        except PdfReadError as exception:
            raise ValueError(f"文件损坏，无法解析: {filename}") from exception

    @staticmethod
    def _try_decrypt(reader: PdfReader, filename: str) -> None:
        try:
            decrypt_result = reader.decrypt("")
        except PdfReadError as exception:
            raise ValueError(f"文件已加密，无法解析: {filename}") from exception
        if decrypt_result == 0:
            raise ValueError(f"文件已加密，无法解析: {filename}")

    @staticmethod
    def _extract_paragraphs(reader: PdfReader, filename: str) -> list[ParsedParagraph]:
        paragraphs: list[ParsedParagraph] = []
        try:
            pages = reader.pages
        except PdfReadError as exception:
            raise ValueError(f"文件已加密，无法解析: {filename}") from exception
        for page_number, page in enumerate(pages, start=1):
            try:
                text = page.extract_text() or ""
            except PdfReadError as exception:
                raise ValueError(f"文件损坏，无法解析: {filename}") from exception
            normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip()
            if not normalized:
                continue
            for block in _PARAGRAPH_SPLIT.split(normalized):
                block = block.strip()
                if block:
                    paragraphs.append(ParsedParagraph(text=block, page_number=page_number))
        return paragraphs
