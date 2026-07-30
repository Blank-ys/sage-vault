from dataclasses import dataclass


@dataclass(frozen=True)
class Chunk:
    """企业文档片段，携带检索与溯源所需的元数据。"""

    chunk_id: str
    knowledge_base_id: int
    document_id: str
    filename: str
    sequence: int
    text: str
    section_title: str | None = None
    page_number: int | None = None
