from collections.abc import AsyncIterator

from sage_vault_rag.model.events import AnswerEvent, Refused, Started

EMPTY_KNOWLEDGE_BASE_MESSAGE = "该知识库暂无可用文档"


class AnsweringService:
    async def answer(self, generation_id: str) -> AsyncIterator[AnswerEvent]:
        yield Started(generation_id)
        yield Refused(generation_id, EMPTY_KNOWLEDGE_BASE_MESSAGE)
