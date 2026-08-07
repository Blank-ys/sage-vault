from pydantic import BaseModel, ConfigDict, Field


class AnswerRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    knowledge_base_id: int = Field(alias="knowledgeBaseId", gt=0)
    question: str = Field(min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    generation_id: str = Field(alias="generationId", min_length=1)


class CancelAnswerRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    generation_id: str = Field(alias="generationId", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)


class IndexingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    task_id: str = Field(alias="taskId", min_length=1)
    attempt: int = Field(ge=1)
    knowledge_base_id: int = Field(alias="knowledgeBaseId", gt=0)
    document_id: str = Field(alias="documentId", min_length=1)
    filename: str = Field(min_length=1)
    source_url: str = Field(alias="sourceUrl", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)


class CleanupRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    task_id: str = Field(alias="taskId", min_length=1)
    knowledge_base_id: int = Field(alias="knowledgeBaseId", gt=0)
    document_id: str = Field(alias="documentId", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
