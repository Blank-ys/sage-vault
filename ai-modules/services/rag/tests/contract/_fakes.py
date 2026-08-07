"""契约测试共享的 fake application adapters 与测试设置。"""

from collections.abc import AsyncIterator

from sage_vault_rag.application.answering.cancellation import CancellationRegistry
from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.bootstrap.dependencies import CleanupRunner, IndexingRunner, RagDependencies
from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.model.cleanup_command import CleanupCommand
from sage_vault_rag.model.events import AnswerEvent
from sage_vault_rag.model.indexing_command import IndexingCommand


class NoOpRegistration:
    async def register(self) -> None:
        pass

    async def close(self) -> None:
        pass


class NoOpRunner:
    async def run(self, command: IndexingCommand | CleanupCommand) -> None:
        pass


class FakeAnsweringService(AnsweringService):
    def __init__(self, events: list[AnswerEvent] | None = None) -> None:
        self._events = events or []
        self._cancellations = CancellationRegistry()

    async def answer(
        self,
        knowledge_base_id: int,
        question: str,
        generation_id: str,
    ) -> AsyncIterator[AnswerEvent]:
        with self._cancellations.track(generation_id):
            for event in self._events:
                yield event


def settings_for_test() -> Settings:
    return Settings(
        signing_key="test-key",
        nacos_server_address="nacos.test:8848",
        embedding_model_path="/dev/null/model",
    )


def dependencies_for_test(
    answering: AnsweringService | None = None,
    indexing: IndexingRunner | None = None,
    cleanup: CleanupRunner | None = None,
) -> RagDependencies:
    return RagDependencies(
        answering=answering or FakeAnsweringService(),
        indexing=indexing or NoOpRunner(),
        cleanup=cleanup or NoOpRunner(),
    )
