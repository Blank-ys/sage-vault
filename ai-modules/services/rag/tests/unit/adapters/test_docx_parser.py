import pytest

from sage_vault_rag.adapters.docx_parser.parser import DocxParser
from sage_vault_rag.model.parsed_document import ParsedDocument
from tests._docx_fixtures import (
    make_docx_with_headings,
    make_docx_with_leading_body,
    make_empty_docx,
    make_multi_paragraph_docx,
    make_text_docx,
    make_whitespace_only_docx,
)


class TestDocxParser:
    @pytest.fixture
    def parser(self) -> DocxParser:
        return DocxParser()

    async def test_parse_single_paragraph_docx_returns_paragraph(self, parser: DocxParser) -> None:
        content = make_text_docx("知识库管理办法")
        document = await parser.parse(content, "regulations.docx")

        assert isinstance(document, ParsedDocument)
        assert len(document.paragraphs) == 1
        assert document.paragraphs[0].text == "知识库管理办法"
        assert document.paragraphs[0].heading is None
        assert document.paragraphs[0].page_number is None

    async def test_parse_multi_paragraph_docx_preserves_order(self, parser: DocxParser) -> None:
        content = make_multi_paragraph_docx(["第一段正文", "第二段正文", "第三段正文"])
        document = await parser.parse(content, "multi.docx")

        assert [p.text for p in document.paragraphs] == ["第一段正文", "第二段正文", "第三段正文"]
        assert all(p.heading is None for p in document.paragraphs)
        assert all(p.page_number is None for p in document.paragraphs)

    async def test_parse_headings_carry_heading_metadata(self, parser: DocxParser) -> None:
        content = make_docx_with_headings(
            [
                (1, "知识库管理办法", ["本办法用于规范公司知识管理。"]),
                (2, "总则", ["第一条 为规范公司知识管理，制定本办法。"]),
            ]
        )
        document = await parser.parse(content, "regulations.docx")

        assert [p.text for p in document.paragraphs] == [
            "知识库管理办法",
            "本办法用于规范公司知识管理。",
            "总则",
            "第一条 为规范公司知识管理，制定本办法。",
        ]
        # 标题段：text 与 heading 均为标题文本
        assert document.paragraphs[0].heading == "知识库管理办法"
        # 正文段：heading 为最近一个标题文本
        assert document.paragraphs[1].heading == "知识库管理办法"
        assert document.paragraphs[2].heading == "总则"
        assert document.paragraphs[3].heading == "总则"
        # DOCX 无页码元数据
        assert all(p.page_number is None for p in document.paragraphs)

    async def test_parse_supports_all_heading_levels(self, parser: DocxParser) -> None:
        content = make_docx_with_headings(
            [
                (1, "一级标题", ["正文一"]),
                (6, "六级标题", ["正文二"]),
            ]
        )
        document = await parser.parse(content, "levels.docx")

        assert [p.text for p in document.paragraphs] == [
            "一级标题",
            "正文一",
            "六级标题",
            "正文二",
        ]
        assert document.paragraphs[1].heading == "一级标题"
        assert document.paragraphs[3].heading == "六级标题"

    async def test_parse_body_before_any_heading_has_none_heading(self, parser: DocxParser) -> None:
        content = make_docx_with_leading_body(
            "开头无标题的正文",
            [(1, "标题", ["标题下正文"])],
        )
        document = await parser.parse(content, "mixed.docx")

        assert document.paragraphs[0].text == "开头无标题的正文"
        assert document.paragraphs[0].heading is None
        assert document.paragraphs[1].text == "标题"
        assert document.paragraphs[1].heading == "标题"
        assert document.paragraphs[2].text == "标题下正文"
        assert document.paragraphs[2].heading == "标题"

    async def test_parse_empty_content_raises_empty_error(self, parser: DocxParser) -> None:
        with pytest.raises(ValueError, match="文档内容为空: empty.docx"):
            await parser.parse(b"", "empty.docx")

    async def test_parse_corrupted_docx_raises_corrupted_error(self, parser: DocxParser) -> None:
        with pytest.raises(ValueError, match="文件损坏，无法解析: broken.docx"):
            await parser.parse(b"This is not a DOCX file", "broken.docx")

    async def test_parse_empty_docx_raises_empty_error(self, parser: DocxParser) -> None:
        """有效但无段落的 DOCX 返回'文档内容为空'。"""
        content = make_empty_docx()
        with pytest.raises(ValueError, match="文档内容为空: blank.docx"):
            await parser.parse(content, "blank.docx")

    async def test_parse_whitespace_only_docx_raises_empty_error(self, parser: DocxParser) -> None:
        """仅含空白段落的 DOCX 返回'文档内容为空'。"""
        content = make_whitespace_only_docx()
        with pytest.raises(ValueError, match="文档内容为空: ws.docx"):
            await parser.parse(content, "ws.docx")
