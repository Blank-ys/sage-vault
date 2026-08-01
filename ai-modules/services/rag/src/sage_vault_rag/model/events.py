from dataclasses import dataclass


@dataclass(frozen=True)
class Started:
    generation_id: str


@dataclass(frozen=True)
class Delta:
    generation_id: str
    delta: str


@dataclass(frozen=True)
class Completed:
    generation_id: str


@dataclass(frozen=True)
class Refused:
    generation_id: str
    message: str


@dataclass(frozen=True)
class Stopped:
    """生成被 Java 显式取消后的终止事件；已产出的 delta 依然有效。"""

    generation_id: str


AnswerEvent = Started | Delta | Completed | Refused | Stopped
