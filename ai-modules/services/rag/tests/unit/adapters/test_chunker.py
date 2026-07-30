from itertools import pairwise

import pytest

from sage_vault_rag.adapters.chunker.chunker import ParagraphChunker
from sage_vault_rag.model.parsed_document import ParsedDocument, ParsedParagraph


def _document(text: str) -> ParsedDocument:
    """按双换行切分文本为段落，模拟 TxtParser 的输出。"""
    paragraphs = [ParsedParagraph(text=part.strip()) for part in text.split("\n\n") if part.strip()]
    return ParsedDocument(paragraphs=paragraphs)


class TestParagraphChunker:
    def test_empty_document_returns_no_chunks(self) -> None:
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        chunks = chunker.split(
            ParsedDocument(paragraphs=[]), knowledge_base_id=1, document_id="doc-1", filename="test.txt"
        )
        assert chunks == []

    def test_short_document_keeps_single_paragraph(self) -> None:
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        chunks = chunker.split(
            _document("第一段。\n\n第二段。"),
            knowledge_base_id=1,
            document_id="doc-1",
            filename="test.txt",
        )
        assert len(chunks) == 1
        assert "第一段。" in chunks[0].text
        assert "第二段。" in chunks[0].text
        assert chunks[0].sequence == 0
        assert chunks[0].knowledge_base_id == 1
        assert chunks[0].document_id == "doc-1"
        assert chunks[0].filename == "test.txt"

    def test_paragraph_boundary_respected(self) -> None:
        chunker = ParagraphChunker(chunk_size=30, chunk_overlap=2)
        chunks = chunker.split(
            _document("A" * 8 + "\n\n" + "B" * 8),
            knowledge_base_id=1,
            document_id="doc-1",
            filename="test.txt",
        )
        assert len(chunks) == 1
        assert "A" * 8 in chunks[0].text
        assert "B" * 8 in chunks[0].text

    def test_long_paragraph_is_split_with_overlap(self) -> None:
        chunker = ParagraphChunker(chunk_size=20, chunk_overlap=4)
        chunks = chunker.split(
            _document("A" * 50),
            knowledge_base_id=1,
            document_id="doc-1",
            filename="test.txt",
        )
        assert len(chunks) > 1
        assert all(len(chunk.text) <= 20 for chunk in chunks)
        # 相邻 chunk 存在重叠
        for previous, current in pairwise(chunks):
            assert previous.text[-4:] == current.text[:4]

    def test_long_document_produces_increasing_sequences(self) -> None:
        chunker = ParagraphChunker(chunk_size=10, chunk_overlap=2)
        chunks = chunker.split(
            _document("A" * 100),
            knowledge_base_id=1,
            document_id="doc-1",
            filename="test.txt",
        )
        sequences = [chunk.sequence for chunk in chunks]
        assert sequences == list(range(len(chunks)))

    def test_invalid_overlap_rejected(self) -> None:
        with pytest.raises(ValueError):
            ParagraphChunker(chunk_size=10, chunk_overlap=10)
        with pytest.raises(ValueError):
            ParagraphChunker(chunk_size=10, chunk_overlap=-1)

    def test_invalid_chunk_size_rejected(self) -> None:
        with pytest.raises(ValueError):
            ParagraphChunker(chunk_size=0, chunk_overlap=0)

    def test_heading_paragraph_is_preserved_in_chunk_text(self) -> None:
        """MarkdownParser 产出的标题段（text 含 ATX 语法）应在 chunk 文本中保留。"""
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="# 总则", heading="总则"),
                ParsedParagraph(text="第一条 为规范公司知识管理，制定本办法。", heading="总则"),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.md")

        assert len(chunks) == 1
        assert "# 总则" in chunks[0].text
        assert "第一条" in chunks[0].text

    def test_heading_propagates_to_chunk_section_title(self) -> None:
        """MD/DOCX 解析器产出的 heading 应写入 Chunk.section_title。"""
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="# 知识库管理办法", heading="知识库管理办法"),
                ParsedParagraph(text="本办法用于规范公司知识管理。", heading="知识库管理办法"),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.md")

        assert len(chunks) == 1
        assert chunks[0].section_title == "知识库管理办法"
        assert chunks[0].page_number is None

    def test_page_number_propagates_to_chunk(self) -> None:
        """PDF 解析器产出的 page_number 应写入 Chunk.page_number。"""
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="第一页内容。", page_number=1),
                ParsedParagraph(text="第一页续。", page_number=1),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.pdf")

        assert len(chunks) == 1
        assert chunks[0].page_number == 1
        assert chunks[0].section_title is None

    def test_merged_paragraphs_keep_first_non_none_heading(self) -> None:
        """合并多段时，首个非空 heading 作为 chunk 的 section_title。"""
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="无标题的前言。", heading=None),
                ParsedParagraph(text="正文内容。", heading="总则"),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.md")

        assert len(chunks) == 1
        assert chunks[0].section_title == "总则"

    def test_merged_paragraphs_across_pages_keep_first_page(self) -> None:
        """跨页合并段落时，首页页码作为 chunk 的 page_number。"""
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="第一页末尾。", page_number=1),
                ParsedParagraph(text="第二页开头。", page_number=2),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.pdf")

        assert len(chunks) == 1
        assert chunks[0].page_number == 1

    def test_long_paragraph_split_inherits_paragraph_metadata(self) -> None:
        """超长段落切分后的子 chunk 应继承原段落的 heading/page_number。"""
        chunker = ParagraphChunker(chunk_size=20, chunk_overlap=4)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="A" * 50, heading="章节", page_number=3),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.pdf")

        assert len(chunks) > 1
        for chunk in chunks:
            assert chunk.section_title == "章节"
            assert chunk.page_number == 3

    def test_flush_and_restart_adopts_new_paragraph_metadata(self) -> None:
        """段落超出 chunk_size 触发 flush 后，新 chunk 采用下一段的元数据。"""
        chunker = ParagraphChunker(chunk_size=10, chunk_overlap=2)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="一二三四五六七八", heading="章节一", page_number=1),
                ParsedParagraph(text="一二三四五六七八", heading="章节二", page_number=2),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.pdf")

        assert len(chunks) == 2
        assert chunks[0].section_title == "章节一"
        assert chunks[0].page_number == 1
        assert chunks[1].section_title == "章节二"
        assert chunks[1].page_number == 2

    def test_txt_paragraphs_without_metadata_default_to_none(self) -> None:
        """TXT 解析器不产出 heading/page_number，Chunk 字段应为 None。"""
        chunker = ParagraphChunker(chunk_size=100, chunk_overlap=10)
        document = ParsedDocument(
            paragraphs=[
                ParsedParagraph(text="第一段。"),
                ParsedParagraph(text="第二段。"),
            ]
        )
        chunks = chunker.split(document, knowledge_base_id=1, document_id="doc-1", filename="test.txt")

        assert len(chunks) == 1
        assert chunks[0].section_title is None
        assert chunks[0].page_number is None
