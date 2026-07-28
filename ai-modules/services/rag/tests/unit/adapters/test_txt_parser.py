import pytest

from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.model.parsed_document import ParsedDocument


class TestTxtParser:
    @pytest.fixture
    def parser(self) -> TxtParser:
        return TxtParser()

    async def test_parse_utf8_text_returns_paragraphs(self, parser: TxtParser) -> None:
        document = await parser.parse("中文内容\n\n第二段。".encode(), "test.txt")

        assert isinstance(document, ParsedDocument)
        assert [p.text for p in document.paragraphs] == ["中文内容", "第二段。"]
        assert all(p.heading is None for p in document.paragraphs)
        assert all(p.page_number is None for p in document.paragraphs)

    async def test_parse_gbk_text(self, parser: TxtParser) -> None:
        original = "这是一段使用 GBK 编码的中文文本，包含足够长度以便编码探测。"
        document = await parser.parse(original.encode("gbk"), "test.txt")

        assert [p.text for p in document.paragraphs] == [original]

    async def test_empty_content_returns_empty_document(self, parser: TxtParser) -> None:
        document = await parser.parse(b"", "test.txt")

        assert document.paragraphs == []

    async def test_line_endings_normalized(self, parser: TxtParser) -> None:
        document = await parser.parse(b"a\r\nb\rc", "test.txt")

        # 单行内换行不拆段，仅做行尾归一化
        assert [p.text for p in document.paragraphs] == ["a\nb\nc"]
        assert "\r" not in document.paragraphs[0].text
