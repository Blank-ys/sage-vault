from dataclasses import dataclass


@dataclass(frozen=True)
class RetrievedChunk:
    """检索召回的文档片段，score 为向量距离（越小越相关）。"""

    chunk_id: str
    document_id: str
    filename: str
    sequence: int
    text: str
    score: float
    section_title: str | None = None
    page_number: int | None = None
