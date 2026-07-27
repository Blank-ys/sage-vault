import pytest

from sage_vault_rag.adapters.txt_parser.parser import TxtParser


class TestTxtParserIntegration:
    @pytest.fixture
    def parser(self) -> TxtParser:
        return TxtParser()

    async def test_parse_representative_chinese_document(self, parser: TxtParser) -> None:
        content = (
            "第一章 总则\n\n"
            "第一条 为规范公司知识管理，制定本办法。\n\n"
            "第二条 本办法适用于全体员工。\n\n"
            "第二章 知识库管理\n\n"
            "第三条 知识库按照主题进行分类维护。"
        ).encode()
        text = await parser.parse(content, "regulations.txt")
        assert "第一章 总则" in text
        assert "第三条 知识库按照主题进行分类维护" in text
        assert "\r" not in text

    async def test_rejects_unreliable_encoding(self, parser: TxtParser) -> None:
        # 随机字节通常会被检测到极低置信度并触发替换字符
        content = bytes(range(128, 256))
        with pytest.raises(ValueError):
            await parser.parse(content, "broken.txt")
