from dataclasses import dataclass


@dataclass(frozen=True)
class Started:
    generation_id: str


@dataclass(frozen=True)
class Delta:
    generation_id: str
    delta: str


@dataclass(frozen=True)
class RetrievedChunkDiagnostic:
    """检索召回片段的诊断信息；只含标识与分数，绝不包含片段正文。"""

    document_id: str
    chunk_id: str
    score: float


@dataclass(frozen=True)
class Completed:
    generation_id: str
    # 检索阶段召回的文档/片段标识与分数（不含正文），用于管理端检索诊断面板。
    retrieval_diagnostics: list[RetrievedChunkDiagnostic]
    # 各阶段毫秒耗时，key 为 embedding / retrieval / generation。
    stage_durations: dict[str, int]
    # 模型侧请求标识；上游未返回时为空。
    model_request_id: str | None = None


@dataclass(frozen=True)
class Refused:
    generation_id: str
    message: str


@dataclass(frozen=True)
class Stopped:
    """生成被 Java 显式取消后的终止事件；已产出的 delta 依然有效。"""

    generation_id: str


@dataclass(frozen=True)
class Failed:
    """生成已经开始，但 RAG 管线在运行时失败，无法完成。

    ``detail`` 只包含对用户安全的、被脱敏后的失败类别（见 ``model.privacy``），
    绝不包含原始异常文本、模型或 trace 标识、知识库 id 等服务端诊断信息。
    那些诊断信息由 ``application.answering.service`` 写入服务器日志，并带追踪标识。
    """

    generation_id: str
    detail: str


AnswerEvent = Started | Delta | Completed | Refused | Stopped | Failed
