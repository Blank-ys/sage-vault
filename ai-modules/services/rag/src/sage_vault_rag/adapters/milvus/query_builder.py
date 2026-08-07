"""Query builder seam：Milvus 表达式构建与转义。"""


class QueryBuilder:
    """构建 Milvus 查询表达式；检索必须按 knowledgeBaseId 强制过滤。"""

    @staticmethod
    def document_expr(document_id: str) -> str:
        """构建按 document_id 过滤的表达式，转义引号与反斜杠。"""
        escaped = document_id.replace("\\", "\\\\").replace('"', '\\"')
        return f'document_id == "{escaped}"'

    @staticmethod
    def knowledge_base_filter(knowledge_base_id: int) -> str:
        """构建强制 knowledgeBaseId 过滤的表达式，保证知识库向量隔离。"""
        return f"knowledge_base_id == {knowledge_base_id}"
