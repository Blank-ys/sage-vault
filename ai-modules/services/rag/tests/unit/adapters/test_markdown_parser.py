import pytest

from sage_vault_rag.adapters.markdown_parser.parser import MarkdownParser
from sage_vault_rag.model.parsed_document import ParsedDocument


class TestMarkdownParser:
    @pytest.fixture
    def parser(self) -> MarkdownParser:
        return MarkdownParser()

    async def test_parse_chinese_markdown_preserves_headings_and_paragraphs(
        self, parser: MarkdownParser
    ) -> None:
        content = (
            "# 知识库管理办法\n\n"
            "本办法用于规范公司知识管理。\n\n"
            "## 总则\n\n"
            "第一条 为规范公司知识管理，制定本办法。\n\n"
            "第二条 本办法适用于全体员工。\n\n"
            "## 知识库分类\n\n"
            "知识库按照主题进行分类维护。"
        ).encode()
        document = await parser.parse(content, "regulations.md")

        assert isinstance(document, ParsedDocument)
        assert [p.text for p in document.paragraphs] == [
            "# 知识库管理办法",
            "本办法用于规范公司知识管理。",
            "## 总则",
            "第一条 为规范公司知识管理，制定本办法。",
            "第二条 本办法适用于全体员工。",
            "## 知识库分类",
            "知识库按照主题进行分类维护。",
        ]
        # 标题段：text 保留完整 ATX 语法，heading 仅保留标题文本（去掉 # 前缀）；
        # 正文段：heading 为最近一个标题的纯文本。
        assert document.paragraphs[0].heading == "知识库管理办法"
        assert document.paragraphs[1].heading == "知识库管理办法"
        assert document.paragraphs[2].heading == "总则"
        assert document.paragraphs[3].heading == "总则"
        assert document.paragraphs[4].heading == "总则"
        assert document.paragraphs[5].heading == "知识库分类"
        assert document.paragraphs[6].heading == "知识库分类"
        # MD 解析器无页码元数据
        assert all(p.page_number is None for p in document.paragraphs)

    async def test_parse_supports_atx_heading_levels(self, parser: MarkdownParser) -> None:
        content = (
            "# 一级标题\n\n"
            "正文一。\n\n"
            "###### 六级标题\n\n"
            "正文二。"
        ).encode()
        document = await parser.parse(content, "headings.md")

        assert [p.text for p in document.paragraphs] == [
            "# 一级标题",
            "正文一。",
            "###### 六级标题",
            "正文二。",
        ]
        assert document.paragraphs[1].heading == "一级标题"
        assert document.paragraphs[3].heading == "六级标题"

    async def test_parse_normalizes_line_endings(self, parser: MarkdownParser) -> None:
        content = "# 标题\r\n\r\n第一段。\r\n第二行。\r\n\r\n第二段。".encode()
        document = await parser.parse(content, "crlf.md")

        # 单段内换行保留为 \n，但 \r 被归一化
        assert [p.text for p in document.paragraphs] == [
            "# 标题",
            "第一段。\n第二行。",
            "第二段。",
        ]
        assert all("\r" not in p.text for p in document.paragraphs)

    async def test_parse_strips_utf8_bom(self, parser: MarkdownParser) -> None:
        content = "\ufeff# 标题\n\n正文。".encode()
        document = await parser.parse(content, "bom.md")

        assert document.paragraphs[0].text == "# 标题"
        assert document.paragraphs[1].text == "正文。"

    async def test_empty_content_raises_understandable_error(self, parser: MarkdownParser) -> None:
        with pytest.raises(ValueError, match="无法从 Markdown 文件中提取任何文本内容: empty.md"):
            await parser.parse(b"", "empty.md")

    async def test_whitespace_only_content_raises(self, parser: MarkdownParser) -> None:
        with pytest.raises(ValueError, match="无法从 Markdown 文件中提取任何文本内容"):
            await parser.parse(b"   \n\n  \n ", "blank.md")

    async def test_unreliable_encoding_raises(self, parser: MarkdownParser) -> None:
        """编码不可靠（低置信度且出现替换字符）时应抛 ValueError，不静默降级。"""
        with pytest.raises(ValueError, match="无法可靠解析 Markdown 文件编码"):
            await parser.parse(bytes(range(128, 256)), "broken.md")

    async def test_heading_inside_multiline_block_is_body(self, parser: MarkdownParser) -> None:
        """多行块中第一行是标题但块内还有正文，整体按正文段处理（不切分标题）。"""
        content = "# 标题\n这行紧跟标题但没有空行分隔。\n\n下一段。".encode()
        document = await parser.parse(content, "edge.md")

        # 第一个块是两行（标题 + 紧跟正文），整体作为正文段，没有标题元数据
        assert document.paragraphs[0].text == "# 标题\n这行紧跟标题但没有空行分隔。"
        assert document.paragraphs[0].heading is None
        assert document.paragraphs[1].text == "下一段。"
        assert document.paragraphs[1].heading is None
