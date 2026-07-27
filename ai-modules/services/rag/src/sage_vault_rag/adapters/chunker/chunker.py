import re
import uuid

from sage_vault_rag.model.chunk import Chunk

_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")
_SENTENCE_END = re.compile(r"[。！？.!?]")


class ParagraphChunker:
    """优先保持自然段落边界，超长段落按长度与重叠切分。"""

    def __init__(self, chunk_size: int, chunk_overlap: int) -> None:
        if chunk_size <= 0:
            raise ValueError("chunk_size 必须大于 0")
        if chunk_overlap < 0 or chunk_overlap >= chunk_size:
            raise ValueError("chunk_overlap 必须满足 0 <= chunk_overlap < chunk_size")
        self._chunk_size = chunk_size
        self._chunk_overlap = chunk_overlap

    def split(self, text: str, knowledge_base_id: int, document_id: str, filename: str) -> list[Chunk]:
        paragraphs = [p.strip() for p in _PARAGRAPH_SPLIT.split(text) if p.strip()]
        chunk_texts: list[str] = []
        current = ""
        for paragraph in paragraphs:
            if len(paragraph) > self._chunk_size:
                if current:
                    chunk_texts.append(current)
                    current = ""
                chunk_texts.extend(self._split_long_paragraph(paragraph))
                continue
            candidate = f"{current}\n\n{paragraph}" if current else paragraph
            if len(candidate) <= self._chunk_size:
                current = candidate
            else:
                chunk_texts.append(current)
                current = paragraph
        if current:
            chunk_texts.append(current)
        return [
            Chunk(
                chunk_id=str(uuid.uuid4()),
                knowledge_base_id=knowledge_base_id,
                document_id=document_id,
                filename=filename,
                sequence=index,
                text=chunk_text,
            )
            for index, chunk_text in enumerate(chunk_texts)
        ]

    def _split_long_paragraph(self, paragraph: str) -> list[str]:
        chunks: list[str] = []
        start = 0
        length = len(paragraph)
        while start < length:
            end = min(start + self._chunk_size, length)
            if end < length:
                end = self._find_split_point(paragraph, start, end)
            chunks.append(paragraph[start:end].strip())
            if end >= length:
                break
            start = max(start + self._chunk_size - self._chunk_overlap, end - self._chunk_overlap)
            start = min(end, start)
        return chunks

    def _find_split_point(self, paragraph: str, start: int, preferred_end: int) -> int:
        search_start = start
        search_end = preferred_end
        while search_end > search_start:
            if _SENTENCE_END.match(paragraph[search_end - 1]):
                return search_end
            search_end -= 1
        return preferred_end
