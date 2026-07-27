import pytest

from sage_vault_rag.adapters.txt_parser.parser import TxtParser


class TestTxtParser:
    @pytest.fixture
    def parser(self) -> TxtParser:
        return TxtParser()

    async def test_parse_utf8_text(self, parser: TxtParser) -> None:
        text = await parser.parse("中文内容\n\n第二段。".encode(), "test.txt")
        assert "中文内容" in text
        assert "第二段。" in text
        assert "\r" not in text

    async def test_parse_gbk_text(self, parser: TxtParser) -> None:
        original = "这是一段使用 GBK 编码的中文文本，包含足够长度以便编码探测。"
        text = await parser.parse(original.encode("gbk"), "test.txt")
        assert "这是一段使用 GBK 编码的中文文本" in text

    async def test_empty_content(self, parser: TxtParser) -> None:
        text = await parser.parse(b"", "test.txt")
        assert text == ""

    async def test_line_endings_normalized(self, parser: TxtParser) -> None:
        text = await parser.parse(b"a\r\nb\rc", "test.txt")
        assert "\r" not in text
        assert text == "a\nb\nc"
