import pytest

from sage_vault_rag.adapters.docx_parser.parser import DocxParser
from sage_vault_rag.model.parsed_document import ParsedDocument
from tests._docx_fixtures import (
    make_docx_with_headings,
    make_empty_docx,
    make_multi_paragraph_docx,
    make_text_docx,
    make_whitespace_only_docx,
)


class TestDocxParserIntegration:
    """DOCX 解析器集成测试：覆盖成功与失败场景，断言可观察文本和来源元数据。

    不绑定 python-docx 内部实现，只验证 ParsedDocument 契约。
    """

    @pytest.fixture
    def parser(self) -> DocxParser:
        return DocxParser()

    async def test_parse_representative_chinese_docx_document(self, parser: DocxParser) -> None:
        """成功解析中文 DOCX，保留段落文本与标题元数据。"""
        content = make_docx_with_headings(
            [
                (1, "知识库管理办法", ["本办法用于规范公司知识管理。"]),
                (2, "总则", ["第一条 为规范公司知识管理，制定本办法。"]),
            ]
        )
        document = await parser.parse(content, "regulations.docx")

        assert isinstance(document, ParsedDocument)
        assert document.paragraphs[0].text == "知识库管理办法"
        assert document.paragraphs[0].heading == "知识库管理办法"
        assert document.paragraphs[1].text == "本办法用于规范公司知识管理。"
        assert document.paragraphs[1].heading == "知识库管理办法"
        assert document.paragraphs[2].text == "总则"
        assert document.paragraphs[2].heading == "总则"
        assert document.paragraphs[3].text == "第一条 为规范公司知识管理，制定本办法。"
        assert document.paragraphs[3].heading == "总则"
        assert all(p.page_number is None for p in document.paragraphs)

    async def test_parse_multi_paragraph_docx_preserves_order(self, parser: DocxParser) -> None:
        """多段 DOCX 按文档顺序保留正文。"""
        content = make_multi_paragraph_docx(["第一段内容", "第二段内容", "第三段内容"])
        document = await parser.parse(content, "multi.docx")

        assert [p.text for p in document.paragraphs] == ["第一段内容", "第二段内容", "第三段内容"]
        assert all(p.heading is None for p in document.paragraphs)

    async def test_parse_corrupted_docx_returns_understandable_error(self, parser: DocxParser) -> None:
        """损坏 DOCX 返回'文件损坏，无法解析'，便于 IndexingService 走失败回调。"""
        with pytest.raises(ValueError, match="文件损坏，无法解析: broken.docx"):
            await parser.parse(b"Not a valid DOCX file content", "broken.docx")

    async def test_parse_empty_docx_returns_understandable_error(self, parser: DocxParser) -> None:
        """有效但无文本的 DOCX 返回'文档内容为空'。"""
        content = make_empty_docx()
        with pytest.raises(ValueError, match="文档内容为空: blank.docx"):
            await parser.parse(content, "blank.docx")

    async def test_parse_whitespace_only_docx_returns_understandable_error(self, parser: DocxParser) -> None:
        """仅含空白段落的 DOCX 返回'文档内容为空'。"""
        content = make_whitespace_only_docx()
        with pytest.raises(ValueError, match="文档内容为空: ws.docx"):
            await parser.parse(content, "ws.docx")

    async def test_parse_empty_content_returns_understandable_error(self, parser: DocxParser) -> None:
        """空字节返回'文档内容为空'。"""
        with pytest.raises(ValueError, match="文档内容为空: empty.docx"):
            await parser.parse(b"", "empty.docx")

    async def test_parse_simple_text_docx(self, parser: DocxParser) -> None:
        """单段 DOCX 能提取中文文本。"""
        content = make_text_docx("员工福利包括带薪年假和健康体检")
        document = await parser.parse(content, "benefits.docx")

        assert document.paragraphs[0].text == "员工福利包括带薪年假和健康体检"
        assert document.paragraphs[0].heading is None
