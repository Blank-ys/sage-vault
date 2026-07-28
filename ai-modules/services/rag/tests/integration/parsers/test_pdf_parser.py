import pytest

from sage_vault_rag.adapters.pdf_parser.parser import PdfParser
from sage_vault_rag.model.parsed_document import ParsedDocument
from tests._pdf_fixtures import (
    make_blank_pdf,
    make_encrypted_pdf,
    make_multi_page_pdf,
    make_text_pdf,
)


class TestPdfParserIntegration:
    """PDF 解析器集成测试：覆盖成功与失败场景，断言可观察文本和来源元数据。

    不绑定 pypdf 内部实现，只验证 ParsedDocument 契约。
    """

    @pytest.fixture
    def parser(self) -> PdfParser:
        return PdfParser()

    async def test_parse_representative_chinese_pdf_document(self, parser: PdfParser) -> None:
        """成功解析带文本层的中文 PDF，保留段落文本与页码元数据。"""
        content = make_text_pdf("知识库管理办法")
        document = await parser.parse(content, "regulations.pdf")

        assert isinstance(document, ParsedDocument)
        assert document.paragraphs[0].text == "知识库管理办法"
        assert document.paragraphs[0].page_number == 1
        assert document.paragraphs[0].heading is None

    async def test_parse_multi_page_pdf_preserves_page_numbers(self, parser: PdfParser) -> None:
        """多页 PDF 每页文本携带正确的页码元数据。"""
        content = make_multi_page_pdf(["第一页内容", "第二页内容", "第三页内容"])
        document = await parser.parse(content, "multi.pdf")

        assert [p.text for p in document.paragraphs] == ["第一页内容", "第二页内容", "第三页内容"]
        assert [p.page_number for p in document.paragraphs] == [1, 2, 3]

    async def test_parse_encrypted_pdf_returns_understandable_error(self, parser: PdfParser) -> None:
        """加密 PDF 返回'文件已加密，无法解析'，便于 IndexingService 走失败回调。"""
        content = make_encrypted_pdf("机密内容")
        with pytest.raises(ValueError, match="文件已加密，无法解析: secret.pdf"):
            await parser.parse(content, "secret.pdf")

    async def test_parse_corrupted_pdf_returns_understandable_error(self, parser: PdfParser) -> None:
        """损坏 PDF 返回'文件损坏，无法解析'。"""
        with pytest.raises(ValueError, match="文件损坏，无法解析: broken.pdf"):
            await parser.parse(b"Not a valid PDF file content", "broken.pdf")

    async def test_parse_blank_or_scanned_pdf_returns_understandable_error(self, parser: PdfParser) -> None:
        """无文本层（空白/扫描版）PDF 返回'未检测到可提取文本'。"""
        content = make_blank_pdf()
        with pytest.raises(ValueError, match="未检测到可提取文本: scanned.pdf"):
            await parser.parse(content, "scanned.pdf")

    async def test_parse_empty_pdf_returns_understandable_error(self, parser: PdfParser) -> None:
        """空内容返回'未检测到可提取文本'。"""
        with pytest.raises(ValueError, match="未检测到可提取文本: empty.pdf"):
            await parser.parse(b"", "empty.pdf")
