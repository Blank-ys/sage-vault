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
        document = await parser.parse(content, "regulations.txt")

        assert [p.text for p in document.paragraphs] == [
            "第一章 总则",
            "第一条 为规范公司知识管理，制定本办法。",
            "第二条 本办法适用于全体员工。",
            "第二章 知识库管理",
            "第三条 知识库按照主题进行分类维护。",
        ]
        # TXT 解析器不识别标题或页码
        assert all(p.heading is None for p in document.paragraphs)
        assert all(p.page_number is None for p in document.paragraphs)

    async def test_rejects_unreliable_encoding(self, parser: TxtParser) -> None:
        # 随机字节通常会被检测到极低置信度并触发替换字符
        content = bytes(range(128, 256))
        with pytest.raises(ValueError):
            await parser.parse(content, "broken.txt")
