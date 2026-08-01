from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class CleanupResult:
    """清理执行结果，由 Java 裁决终态。"""

    task_id: str
    document_id: str
    success: bool
    diagnostics: dict[str, Any] = field(default_factory=dict)
