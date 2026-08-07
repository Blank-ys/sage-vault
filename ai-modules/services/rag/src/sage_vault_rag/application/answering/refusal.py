"""证据判定阶段：根据召回片段决定拒答或继续生成。"""

from sage_vault_rag.model.retrieved_chunk import RetrievedChunk

EMPTY_KNOWLEDGE_BASE_MESSAGE = "该知识库暂无可用文档"
WEAK_EVIDENCE_MESSAGE = "未找到足够相关的文档内容"


class RefusalPolicy:
    """空召回或弱证据时返回拒答文案，否则返回 None 表示继续生成。"""

    def __init__(self, refusal_threshold: float) -> None:
        self._refusal_threshold = refusal_threshold

    def judge(self, chunks: list[RetrievedChunk]) -> str | None:
        if not chunks:
            return EMPTY_KNOWLEDGE_BASE_MESSAGE
        if chunks[0].score > self._refusal_threshold:
            return WEAK_EVIDENCE_MESSAGE
        return None
