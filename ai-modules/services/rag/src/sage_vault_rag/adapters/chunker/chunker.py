import re
import uuid

from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.parsed_document import ParsedDocument

_SENTENCE_END = re.compile(r"[。！？.!?]")


class ParagraphChunker:
    """优先保持自然段边界，超长段落按长度与重叠切分。

    输入为 `ParsedDocument`：解析器已按自然段（可能带标题/页码元数据）切分；
    本 chunker 在此基础上按 `chunk_size` 合并相邻自然段，超长自然段按句子边界
    与 `chunk_overlap` 切分。

    元数据传播（03e）：合并多段形成的 chunk 取首个非空 heading 作为 `section_title`、
    首个非空 page_number 作为 `page_number`；超长段落切分后的子 chunk 继承原段落元数据。
    """

    def __init__(self, chunk_size: int, chunk_overlap: int) -> None:
        if chunk_size <= 0:
            raise ValueError("chunk_size 必须大于 0")
        if chunk_overlap < 0 or chunk_overlap >= chunk_size:
            raise ValueError("chunk_overlap 必须满足 0 <= chunk_overlap < chunk_size")
        self._chunk_size = chunk_size
        self._chunk_overlap = chunk_overlap

    def split(
        self,
        document: ParsedDocument,
        knowledge_base_id: int,
        document_id: str,
        filename: str,
    ) -> list[Chunk]:
        chunks: list[Chunk] = []
        current_text = ""
        current_heading: str | None = None
        current_page: int | None = None
        for paragraph in document.paragraphs:
            paragraph_text = paragraph.text
            paragraph_heading = paragraph.heading
            paragraph_page = paragraph.page_number
            if len(paragraph_text) > self._chunk_size:
                if current_text:
                    chunks.append(
                        self._build_chunk(
                            current_text,
                            current_heading,
                            current_page,
                            knowledge_base_id=knowledge_base_id,
                            document_id=document_id,
                            filename=filename,
                            sequence=len(chunks),
                        )
                    )
                    current_text = ""
                    current_heading = None
                    current_page = None
                for sub_text in self._split_long_paragraph(paragraph_text):
                    chunks.append(
                        self._build_chunk(
                            sub_text,
                            paragraph_heading,
                            paragraph_page,
                            knowledge_base_id=knowledge_base_id,
                            document_id=document_id,
                            filename=filename,
                            sequence=len(chunks),
                        )
                    )
                continue
            if current_text:
                candidate = f"{current_text}\n\n{paragraph_text}"
            else:
                candidate = paragraph_text
                current_heading = paragraph_heading
                current_page = paragraph_page
            if len(candidate) <= self._chunk_size:
                current_text = candidate
                if current_heading is None and paragraph_heading is not None:
                    current_heading = paragraph_heading
                if current_page is None and paragraph_page is not None:
                    current_page = paragraph_page
            else:
                chunks.append(
                    self._build_chunk(
                        current_text,
                        current_heading,
                        current_page,
                        knowledge_base_id=knowledge_base_id,
                        document_id=document_id,
                        filename=filename,
                        sequence=len(chunks),
                    )
                )
                current_text = paragraph_text
                current_heading = paragraph_heading
                current_page = paragraph_page
        if current_text:
            chunks.append(
                self._build_chunk(
                    current_text,
                    current_heading,
                    current_page,
                    knowledge_base_id=knowledge_base_id,
                    document_id=document_id,
                    filename=filename,
                    sequence=len(chunks),
                )
            )
        return chunks

    @staticmethod
    def _build_chunk(
        text: str,
        section_title: str | None,
        page_number: int | None,
        *,
        knowledge_base_id: int,
        document_id: str,
        filename: str,
        sequence: int,
    ) -> Chunk:
        return Chunk(
            chunk_id=str(uuid.uuid4()),
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
            filename=filename,
            sequence=sequence,
            text=text,
            section_title=section_title,
            page_number=page_number,
        )

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
