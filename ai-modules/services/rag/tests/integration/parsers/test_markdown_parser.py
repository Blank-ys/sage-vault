import pytest

from sage_vault_rag.adapters.markdown_parser.parser import MarkdownParser
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.model.parsed_document import ParsedDocument


class TestMarkdownParserIntegration:
    @pytest.fixture
    def parser(self) -> MarkdownParser:
        return MarkdownParser()

    async def test_parse_representative_chinese_markdown_document(self, parser: MarkdownParser) -> None:
        content = (
            "# 知识库管理办法\n\n"
            "本办法用于规范公司知识管理。\n\n"
            "## 总则\n\n"
            "第一条 为规范公司知识管理，制定本办法。\n\n"
            "第二条 本办法适用于全体员工。\n\n"
            "## 知识库分类\n\n"
            "知识库按照主题进行分类维护，包含技术、产品与运营三类。"
        ).encode()
        document = await parser.parse(content, "regulations.md")

        assert isinstance(document, ParsedDocument)
        # 标题与正文段都按出现顺序保留
        assert document.paragraphs[0].text == "# 知识库管理办法"
        assert document.paragraphs[1].text == "本办法用于规范公司知识管理。"
        assert document.paragraphs[2].text == "## 总则"
        assert document.paragraphs[3].text == "第一条 为规范公司知识管理，制定本办法。"
        assert document.paragraphs[6].text.startswith("知识库按照主题进行分类维护")
        # 标题元数据正确传播（heading 已去掉 # 前缀，仅保留标题文本）
        assert document.paragraphs[1].heading == "知识库管理办法"
        assert document.paragraphs[3].heading == "总则"
        assert document.paragraphs[6].heading == "知识库分类"
        # MD 不带页码
        assert all(p.page_number is None for p in document.paragraphs)

    async def test_parse_md_and_txt_share_parsed_document_contract(self) -> None:
        """MD 与 TXT 解析器都返回 ParsedDocument，便于 IndexingService 统一处理。"""
        md_parser = MarkdownParser()
        txt_parser = TxtParser()

        md_doc = await md_parser.parse("# 标题\n\n正文。".encode(), "test.md")
        txt_doc = await txt_parser.parse("正文一。\n\n正文二。".encode(), "test.txt")

        assert isinstance(md_doc, ParsedDocument)
        assert isinstance(txt_doc, ParsedDocument)
        # 两端都可以用同一份 ChunkerPort 处理
        assert len(md_doc.paragraphs) == 2
        assert len(txt_doc.paragraphs) == 2
