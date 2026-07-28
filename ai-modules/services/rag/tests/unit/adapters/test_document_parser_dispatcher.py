import pytest

from sage_vault_rag.adapters.document_parser.dispatcher import FormatDispatchingDocumentParser
from sage_vault_rag.adapters.markdown_parser.parser import MarkdownParser
from sage_vault_rag.adapters.txt_parser.parser import TxtParser
from sage_vault_rag.model.parsed_document import ParsedDocument


class TestFormatDispatchingDocumentParser:
    @pytest.fixture
    def dispatcher(self) -> FormatDispatchingDocumentParser:
        return FormatDispatchingDocumentParser(
            {
                "txt": TxtParser(),
                "md": MarkdownParser(),
            }
        )

    async def test_dispatches_to_txt_parser(self, dispatcher: FormatDispatchingDocumentParser) -> None:
        document = await dispatcher.parse("第一段。\n\n第二段。".encode(), "doc.txt")

        assert isinstance(document, ParsedDocument)
        assert [p.text for p in document.paragraphs] == ["第一段。", "第二段。"]
        assert all(p.heading is None for p in document.paragraphs)

    async def test_dispatches_to_markdown_parser(self, dispatcher: FormatDispatchingDocumentParser) -> None:
        document = await dispatcher.parse("# 标题\n\n正文。".encode(), "doc.md")

        assert isinstance(document, ParsedDocument)
        assert document.paragraphs[0].text == "# 标题"
        assert document.paragraphs[0].heading == "标题"
        assert document.paragraphs[1].text == "正文。"
        assert document.paragraphs[1].heading == "标题"

    async def test_extension_matching_is_case_insensitive(
        self, dispatcher: FormatDispatchingDocumentParser
    ) -> None:
        document = await dispatcher.parse("# 标题\n\n正文。".encode(), "UPPER.MD")

        assert document.paragraphs[0].text == "# 标题"

    async def test_unsupported_extension_raises(self, dispatcher: FormatDispatchingDocumentParser) -> None:
        with pytest.raises(ValueError, match="不支持的文档格式: report.pdf"):
            await dispatcher.parse(b"...", "report.pdf")

    async def test_missing_extension_raises(self, dispatcher: FormatDispatchingDocumentParser) -> None:
        with pytest.raises(ValueError, match="不支持的文档格式: noext"):
            await dispatcher.parse(b"...", "noext")

    async def test_empty_parsers_map_rejected(self) -> None:
        with pytest.raises(ValueError, match="必须注册至少一种文档解析器"):
            FormatDispatchingDocumentParser({})
