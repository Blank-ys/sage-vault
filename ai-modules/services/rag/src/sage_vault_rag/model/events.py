from dataclasses import dataclass


@dataclass(frozen=True)
class Started:
    generation_id: str


@dataclass(frozen=True)
class Refused:
    generation_id: str
    message: str


AnswerEvent = Started | Refused
