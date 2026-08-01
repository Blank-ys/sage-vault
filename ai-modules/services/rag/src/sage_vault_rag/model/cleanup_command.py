from dataclasses import dataclass


@dataclass(frozen=True)
class CleanupCommand:
    """Java 派发的单文档清理命令。"""

    task_id: str
    knowledge_base_id: int
    document_id: str
    request_id: str
