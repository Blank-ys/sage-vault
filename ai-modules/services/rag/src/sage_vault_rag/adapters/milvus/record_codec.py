"""Record codec seam：Chunk 与 Milvus 实体之间的双向编解码。

pymilvus 2.4.x 的 FieldSchema 静默忽略 nullable=True，VARCHAR/INT64 字段
不支持 None 值入库。section_title/page_number 在写入时以空字符串/0 哨兵
替代 None，召回时再转回 None；双向转换只发生在此 seam 内。
"""

from typing import Any, ClassVar

from sage_vault_rag.model.chunk import Chunk
from sage_vault_rag.model.retrieved_chunk import RetrievedChunk


class RecordCodec:
    """Chunk <-> Milvus 实体编解码，集中哨兵值与字段映射。"""

    search_output_fields: ClassVar[list[str]] = [
        "chunk_id",
        "document_id",
        "filename",
        "sequence",
        "text",
        "section_title",
        "page_number",
    ]

    def encode(self, chunks: list[Chunk], vectors: list[list[float]]) -> list[list[Any]]:
        """把 Chunk 与向量转换为 Milvus 列式实体；None 元数据转为哨兵值。"""
        return [
            [chunk.chunk_id for chunk in chunks],
            [chunk.knowledge_base_id for chunk in chunks],
            [chunk.document_id for chunk in chunks],
            [chunk.filename for chunk in chunks],
            [chunk.sequence for chunk in chunks],
            [chunk.text for chunk in chunks],
            [chunk.section_title if chunk.section_title is not None else "" for chunk in chunks],
            [chunk.page_number if chunk.page_number is not None else 0 for chunk in chunks],
            vectors,
        ]

    def decode(self, hits: list[Any]) -> list[RetrievedChunk]:
        """把 search hits 映射为 RetrievedChunk；哨兵值转回 None。"""
        decoded: list[RetrievedChunk] = []
        for hit in hits:
            section_title_raw = hit.entity.get("section_title")
            page_number_raw = hit.entity.get("page_number")
            decoded.append(
                RetrievedChunk(
                    chunk_id=hit.entity.get("chunk_id"),
                    document_id=hit.entity.get("document_id"),
                    filename=hit.entity.get("filename"),
                    sequence=hit.entity.get("sequence"),
                    text=hit.entity.get("text"),
                    score=float(hit.distance),
                    section_title=section_title_raw if section_title_raw != "" else None,
                    page_number=page_number_raw if page_number_raw != 0 else None,
                )
            )
        return decoded
