from dataclasses import dataclass


@dataclass(frozen=True)
class IndexingCommand:
    """Java 派发的单文档入库命令。"""

    task_id: str
    attempt: int
    knowledge_base_id: int
    document_id: str
    filename: str
    source_url: str
