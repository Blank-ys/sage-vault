from sage_vault_rag.model.indexing_result import IndexingResult


class JavaCallbackClient:
    """向 Java 报告入库结果；V1 由 02c 接入真实回调地址。"""

    async def report(self, result: IndexingResult) -> None:
        # 02c 将扩展契约并接入真实 HTTP 回调；当前保留端口边界。
        pass
