"""已组装对象图的契约：transport 只消费这些 application-facing interface。"""

from dataclasses import dataclass
from typing import Protocol

from sage_vault_rag.application.answering.service import AnsweringService
from sage_vault_rag.model.cleanup_command import CleanupCommand
from sage_vault_rag.model.indexing_command import IndexingCommand


class IndexingRunner(Protocol):
    async def run(self, command: IndexingCommand) -> None: ...


class CleanupRunner(Protocol):
    async def run(self, command: CleanupCommand) -> None: ...


class ServiceRegistration(Protocol):
    async def register(self) -> None: ...

    async def close(self) -> None: ...


@dataclass(frozen=True)
class RagDependencies:
    """transport 需要的已组装服务集合；由 bootstrap 生产，契约测试注入假实现。"""

    answering: AnsweringService
    indexing: IndexingRunner
    cleanup: CleanupRunner
