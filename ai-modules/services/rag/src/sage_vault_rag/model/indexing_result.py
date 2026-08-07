from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class IndexingResult:
    """入库执行结果，由 Java 裁决终态；回调只传递本结果，不触发补偿。"""

    task_id: str
    attempt: int
    document_id: str
    success: bool
    chunks_count: int = 0
    diagnostics: dict[str, Any] = field(default_factory=dict)
