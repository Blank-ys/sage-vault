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
