from typing import Protocol

from sage_vault_rag.model.parsed_document import ParsedDocument


class DocumentParserPort(Protocol):
    """将原文件字节解析为结构化文档（自然段列表 + 可选标题/页码元数据）。

    返回 `ParsedDocument`，使下游切块器能够：
    - 保留自然段边界；
    - 优先保留标题信息（Markdown ATX 标题 / 未来 PDF/DOCX 章节）；
    - 在后续工单中把来源元数据写入片段，供检索回显与溯源使用。

    无法解析（空文件、损坏、加密、不支持格式等）时抛出已注册的业务异常；
    解析器不得在内部吞掉异常或返回静默降级结果。
    """

    async def parse(self, content: bytes, filename: str) -> ParsedDocument:
        """返回按文档顺序排列的结构化文档；无法解析时抛出异常。"""
        ...
