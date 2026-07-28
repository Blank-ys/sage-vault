import pytest

from sage_vault_rag.adapters.pdf_parser.parser import PdfParser
from sage_vault_rag.model.parsed_document import ParsedDocument
from tests._pdf_fixtures import (
    make_blank_pdf,
    make_encrypted_pdf,
    make_multi_page_pdf,
    make_text_pdf,
)


class TestPdfParser:
    @pytest.fixture
    def parser(self) -> PdfParser:
        return PdfParser()

    async def test_parse_single_page_pdf_returns_paragraphs_with_page_number(
        self, parser: PdfParser
    ) -> None:
        content = make_text_pdf("知识库管理办法")
        document = await parser.parse(content, "regulations.pdf")

        assert isinstance(document, ParsedDocument)
        assert len(document.paragraphs) == 1
        assert document.paragraphs[0].text == "知识库管理办法"
        assert document.paragraphs[0].page_number == 1
        assert document.paragraphs[0].heading is None

    async def test_parse_multi_page_pdf_records_per_page_number(self, parser: PdfParser) -> None:
        content = make_multi_page_pdf(["第一页正文", "第二页正文", "第三页正文"])
        document = await parser.parse(content, "multi.pdf")

        assert len(document.paragraphs) == 3
        assert [p.text for p in document.paragraphs] == ["第一页正文", "第二页正文", "第三页正文"]
        assert [p.page_number for p in document.paragraphs] == [1, 2, 3]

    async def test_parse_empty_content_raises_no_extractable_text(self, parser: PdfParser) -> None:
        with pytest.raises(ValueError, match="未检测到可提取文本: empty.pdf"):
            await parser.parse(b"", "empty.pdf")

    async def test_parse_corrupted_pdf_raises_corrupted_error(self, parser: PdfParser) -> None:
        with pytest.raises(ValueError, match="文件损坏，无法解析: broken.pdf"):
            await parser.parse(b"This is not a PDF file", "broken.pdf")

    async def test_parse_encrypted_pdf_raises_encrypted_error(self, parser: PdfParser) -> None:
        content = make_encrypted_pdf("机密内容")
        with pytest.raises(ValueError, match="文件已加密，无法解析: secret.pdf"):
            await parser.parse(content, "secret.pdf")

    async def test_parse_blank_pdf_raises_no_extractable_text(self, parser: PdfParser) -> None:
        """无文本层的 PDF（空白或扫描版）应抛出'未检测到可提取文本'。"""
        content = make_blank_pdf()
        with pytest.raises(ValueError, match="未检测到可提取文本: blank.pdf"):
            await parser.parse(content, "blank.pdf")

    async def test_parse_pdf_paragraphs_carry_no_heading(self, parser: PdfParser) -> None:
        """PDF 文本层无章节结构信息，heading 始终为 None。"""
        content = make_text_pdf("正文内容")
        document = await parser.parse(content, "doc.pdf")

        assert all(p.heading is None for p in document.paragraphs)

    async def test_parse_empty_password_encrypted_pdf_extracts_text(self, parser: PdfParser) -> None:
        """空密码加密的 PDF（DRM 保护但允许阅读）应能正常提取文本。"""
        content = make_encrypted_pdf("可读内容", password="")
        document = await parser.parse(content, "drm.pdf")

        assert len(document.paragraphs) == 1
        assert document.paragraphs[0].text == "可读内容"
        assert document.paragraphs[0].page_number == 1
