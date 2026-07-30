import json
import os
import time
import unittest
import urllib.error
import urllib.request
import uuid


def build_multipart(fields: dict[str, str], files: dict[str, tuple[str, bytes, str]]) -> tuple[bytes, str]:
    """构造 multipart/form-data 请求体。"""
    boundary = uuid.uuid4().hex
    lines: list[bytes] = []
    for key, value in fields.items():
        lines.append(f"--{boundary}".encode())
        lines.append(f'Content-Disposition: form-data; name="{key}"'.encode())
        lines.append(b"")
        lines.append(str(value).encode())
    for key, (filename, content, content_type) in files.items():
        lines.append(f"--{boundary}".encode())
        lines.append(
            f'Content-Disposition: form-data; name="{key}"; filename="{filename}"'.encode()
        )
        lines.append(f"Content-Type: {content_type}".encode())
        lines.append(b"")
        lines.append(content)
    lines.append(f"--{boundary}--".encode())
    lines.append(b"")
    body = b"\r\n".join(lines)
    return body, f"multipart/form-data; boundary={boundary}"


class _KnowledgeQaSystemTestBase(unittest.TestCase):
    """系统验收公共基类：提供 Gateway HTTP 请求与轮询辅助。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.base_url = os.environ["SAGE_VAULT_GATEWAY_URL"].rstrip("/")
        cls.admin_token = os.environ["SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN"]
        cls.user_token = os.environ["SAGE_VAULT_GENERAL_USER_TOKEN"]

    def request_json(
        self,
        method: str,
        path: str,
        token: str | None,
        body: dict[str, object] | None = None,
    ) -> tuple[int, str]:
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(
            self.base_url + path,
            method=method,
            headers=headers,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def request_multipart(
        self,
        method: str,
        path: str,
        token: str | None,
        fields: dict[str, str],
        files: dict[str, tuple[str, bytes, str]],
    ) -> tuple[int, str]:
        body, content_type = build_multipart(fields, files)
        headers = {"Content-Type": content_type}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(
            self.base_url + path,
            method=method,
            headers=headers,
            data=body,
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def request_stream(
        self,
        method: str,
        path: str,
        token: str | None,
        body: dict[str, object],
    ) -> tuple[int, str]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(
            self.base_url + path,
            method=method,
            headers=headers,
            data=json.dumps(body, ensure_ascii=False).encode(),
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def wait_for_status(
        self,
        knowledge_base_id: int,
        filename: str,
        expected_status: str,
        timeout: int = 180,
    ) -> dict[str, object] | None:
        """轮询文档列表，直到指定文件名的文档达到预期状态或超时。"""
        deadline = time.time() + timeout
        last_seen: dict[str, object] | None = None
        while time.time() < deadline:
            _, body = self.request_json(
                "GET",
                f"/ruoyi-kb-management/documents?knowledgeBaseId={knowledge_base_id}",
                self.admin_token,
            )
            result = json.loads(body)
            if result.get("code") == 200:
                for doc in result["data"]:
                    if doc["filename"] == filename:
                        last_seen = doc
                        if doc["status"] == expected_status:
                            return doc
                        break
            time.sleep(3)
        return last_seen

    def create_knowledge_base(self, unique: str) -> int:
        """创建知识库并返回 ID。"""
        _, body = self.request_json(
            "POST",
            "/ruoyi-kb-management/knowledge-bases",
            self.admin_token,
            {"name": unique, "description": "05 system test"},
        )
        result = json.loads(body)
        assert result["code"] == 200, f"创建知识库应成功: {body}"
        return result["data"]["id"]

    def ask_question(self, conversation_id: int, question: str, request_id: str) -> str:
        """发起问答并返回完整 SSE 流文本。"""
        _, stream = self.request_stream(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": question, "requestId": request_id},
        )
        return stream

    def assert_refused(self, stream: str) -> None:
        """断言流中包含拒答事件且包含空知识库提示。"""
        assert "event:refused" in stream, f"应拒答: {stream}"
        assert "该知识库暂无可用文档" in stream, f"应提示无可用文档: {stream}"


class RetryAndAtomicPublicationSystemTest(_KnowledgeQaSystemTestBase):
    """05 系统验收：注入解析失败，证明失败/重试期间没有部分内容被检索。

    黑盒路径：浏览器 -> Gateway -> Java kb-management -> Python RAG -> Milvus。
    不直连 Python、MinIO 或数据库，只通过 Gateway 观察 HTTP 行为。
    """

    def test_failure_retry_and_atomic_publication(self) -> None:
        unique = f"sys05-{int(time.time())}"

        # 1. 创建知识库
        _, kb_body = self.request_json(
            "POST",
            "/ruoyi-kb-management/knowledge-bases",
            self.admin_token,
            {"name": unique, "description": "retry and atomic publication system test"},
        )
        kb = json.loads(kb_body)
        self.assertEqual(200, kb["code"], "创建知识库应成功")
        knowledge_base_id = kb["data"]["id"]

        try:
            self._assert_failed_document_not_retrievable(knowledge_base_id, unique)
            self._assert_available_document_retrievable(knowledge_base_id, unique)
            self._assert_retry_state_conflict(knowledge_base_id, unique)
        finally:
            # 系统测试不删除残留数据；使用时间戳唯一名避免冲突，便于人工排查。
            pass

    def _assert_failed_document_not_retrievable(self, knowledge_base_id: int, unique: str) -> None:
        """注入解析失败（空 MD），验证失败期间没有部分内容被检索。"""
        # 上传空 MD 文件 —— MarkdownParser 会对空内容抛 ValueError
        _, upload_body = self.request_multipart(
            "POST",
            "/ruoyi-kb-management/documents",
            self.admin_token,
            {"knowledgeBaseId": str(knowledge_base_id)},
            {"file": (f"empty-{unique}.md", b"", "text/markdown")},
        )
        upload = json.loads(upload_body)
        self.assertEqual(200, upload["code"], "上传空 MD 应返回 200（异步处理）")
        md_document_id = upload["data"]["id"]
        md_filename = upload["data"]["filename"]
        self.assertEqual("PROCESSING", upload["data"]["status"], "上传后初始状态应为 PROCESSING")

        # 轮询直到 FAILED
        failed_doc = self.wait_for_status(knowledge_base_id, md_filename, "FAILED", timeout=180)
        self.assertIsNotNone(failed_doc, "空 MD 文档应在超时前进入 FAILED 状态")
        self.assertEqual("FAILED", failed_doc["status"])
        self.assertIn("RAG 入库失败", failed_doc["errorMessage"], "失败原因应保留可诊断信息")

        # 创建会话并提问 —— 应返回拒答（该知识库暂无可用文档），证明没有部分内容被检索
        _, conv_body = self.request_json(
            "POST",
            "/ruoyi-kb-management/conversations",
            self.user_token,
            {"knowledgeBaseId": knowledge_base_id},
        )
        conv = json.loads(conv_body)
        self.assertEqual(200, conv["code"])
        conversation_id = conv["data"]["id"]

        _, stream = self.request_stream(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": "这里有什么内容？", "requestId": f"sys05-fail-{unique}"},
        )
        self.assertIn("event:started", stream)
        self.assertIn("event:refused", stream)
        self.assertIn("该知识库暂无可用文档", stream, "失败文档不应产生任何可检索片段")

        # 重试失败的 MD —— 空文件仍会失败，验证重试路径走通且不创建重复文档
        _, retry_body = self.request_json(
            "POST",
            f"/ruoyi-kb-management/documents/{md_document_id}/retry",
            self.admin_token,
        )
        retry = json.loads(retry_body)
        self.assertEqual(200, retry["code"], "重试 FAILED 文档应成功发起")
        self.assertEqual("PROCESSING", retry["data"]["status"], "重试后状态应回到 PROCESSING")
        self.assertEqual(md_document_id, retry["data"]["id"], "重试保留文档身份")
        self.assertEqual(md_filename, retry["data"]["filename"], "重试保留文件名")

        # 等待重试完成（MD 仍为空，再次失败）
        retry_failed = self.wait_for_status(knowledge_base_id, md_filename, "FAILED", timeout=180)
        self.assertIsNotNone(retry_failed, "重试后的空 MD 应再次进入 FAILED 状态")

        # 重试后提问 —— 仍应拒答，证明重试期间没有部分内容被检索
        _, stream_after_retry = self.request_stream(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": "重试后有什么内容？", "requestId": f"sys05-retry-{unique}"},
        )
        self.assertIn("event:refused", stream_after_retry)
        self.assertIn("该知识库暂无可用文档", stream_after_retry)

        # 重复回调不会产生非法状态：文档列表中该 MD 仍只有一条记录
        _, list_body = self.request_json(
            "GET",
            f"/ruoyi-kb-management/documents?knowledgeBaseId={knowledge_base_id}",
            self.admin_token,
        )
        docs = json.loads(list_body)
        md_docs = [d for d in docs["data"] if d["filename"] == md_filename]
        self.assertEqual(1, len(md_docs), "重试不应创建重复文档")

    def _assert_available_document_retrievable(self, knowledge_base_id: int, unique: str) -> None:
        """上传有效 TXT，验证只有 AVAILABLE 文档参与问答。"""
        distinctive_content = f"Sage Vault 验收测试唯一标记 {unique}"
        txt_content = f"{distinctive_content}\n\n这是用于验证原子发布的有效文档内容。".encode()

        _, upload_body = self.request_multipart(
            "POST",
            "/ruoyi-kb-management/documents",
            self.admin_token,
            {"knowledgeBaseId": str(knowledge_base_id)},
            {"file": (f"evidence-{unique}.txt", txt_content, "text/plain")},
        )
        upload = json.loads(upload_body)
        self.assertEqual(200, upload["code"], "上传有效 TXT 应返回 200")
        txt_filename = upload["data"]["filename"]

        # 轮询直到 AVAILABLE
        available_doc = self.wait_for_status(knowledge_base_id, txt_filename, "AVAILABLE", timeout=180)
        self.assertIsNotNone(available_doc, "有效 TXT 应在超时前进入 AVAILABLE 状态")
        self.assertEqual("AVAILABLE", available_doc["status"])

        # 创建会话并提问 —— 应返回 delta/completed（非 refused），证明 AVAILABLE 文档可检索
        _, conv_body = self.request_json(
            "POST",
            "/ruoyi-kb-management/conversations",
            self.user_token,
            {"knowledgeBaseId": knowledge_base_id},
        )
        conv = json.loads(conv_body)
        self.assertEqual(200, conv["code"])
        conversation_id = conv["data"]["id"]

        _, stream = self.request_stream(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": distinctive_content, "requestId": f"sys05-ok-{unique}"},
        )
        self.assertIn("event:started", stream)
        self.assertIn("event:completed", stream, "有效文档应完成回答而非拒答")
        self.assertNotIn("event:refused", stream, "AVAILABLE 文档不应触发拒答")
        self.assertIn(distinctive_content, stream, "回答应包含已索引的唯一内容")

    def _assert_retry_state_conflict(self, knowledge_base_id: int, unique: str) -> None:
        """对 AVAILABLE 文档发起重试应返回 DOCUMENT_STATE_CONFLICT(410014)。"""
        _, list_body = self.request_json(
            "GET",
            f"/ruoyi-kb-management/documents?knowledgeBaseId={knowledge_base_id}",
            self.admin_token,
        )
        docs = json.loads(list_body)
        available_docs = [d for d in docs["data"] if d["status"] == "AVAILABLE"]
        self.assertGreaterEqual(len(available_docs), 1, "应至少有一个 AVAILABLE 文档")

        txt_document_id = available_docs[0]["id"]
        _, conflict_body = self.request_json(
            "POST",
            f"/ruoyi-kb-management/documents/{txt_document_id}/retry",
            self.admin_token,
        )
        conflict = json.loads(conflict_body)
        self.assertEqual(
            410014,
            conflict["code"],
            "对 AVAILABLE 文档重试应返回 DOCUMENT_STATE_CONFLICT",
        )


class StageFailureInjectionSystemTest(_KnowledgeQaSystemTestBase):
    """05 系统验收：注入各阶段失败，证明失败期间没有部分内容被检索，
    成功重试后可检索到完整内容。

    需要 Python RAG 服务以环境变量 ``SAGE_VAULT_RAG_TEST_FAILURE_FLAG_FILE``
    指向 flag 文件；测试通过 ``SAGE_VAULT_TEST_FAILURE_FLAG_FILE`` 指向同一文件。
    未设置时跳过本测试。flag 文件内容为 ``chunk``/``embed``/``vector`` 时
    对应阶段的适配器包装器注入失败；清空后重试可正常成功。
    """

    @classmethod
    def setUpClass(cls) -> None:
        super().setUpClass()
        cls.flag_file = os.environ.get("SAGE_VAULT_TEST_FAILURE_FLAG_FILE", "")
        if not cls.flag_file:
            raise unittest.SkipTest(
                "需要 SAGE_VAULT_TEST_FAILURE_FLAG_FILE 环境变量指向故障注入 flag 文件"
            )

    def setUp(self) -> None:
        self._write_flag("")

    def tearDown(self) -> None:
        self._write_flag("")

    def _write_flag(self, stage: str) -> None:
        with open(self.flag_file, "w", encoding="utf-8") as handle:
            handle.write(stage)

    def test_stage_failures_and_successful_retry(self) -> None:
        unique = f"sys05s-{int(time.time())}"
        knowledge_base_id = self.create_knowledge_base(unique)

        try:
            failed_doc_ids = self._assert_each_stage_failure_not_retrievable(
                knowledge_base_id, unique
            )
            self._assert_successful_retry_is_retrievable(
                knowledge_base_id, unique, failed_doc_ids[0]
            )
        finally:
            self._write_flag("")

    def _assert_each_stage_failure_not_retrievable(
        self, knowledge_base_id: int, unique: str
    ) -> list[int]:
        """对 chunk/embed/vector 三个阶段分别注入失败，验证失败期间无部分内容可检索。"""
        _, conv_body = self.request_json(
            "POST",
            "/ruoyi-kb-management/conversations",
            self.user_token,
            {"knowledgeBaseId": knowledge_base_id},
        )
        conv = json.loads(conv_body)
        self.assertEqual(200, conv["code"])
        conversation_id = conv["data"]["id"]

        failed_doc_ids: list[int] = []
        for stage in ("chunk", "embed", "vector"):
            with self.subTest(stage=stage):
                self._write_flag(stage)
                distinctive = f"阶段失败标记 {unique} {stage}"
                content = f"{distinctive}\n\n用于验证原子发布的有效文档内容。".encode()
                filename = f"fail-{stage}-{unique}.txt"

                _, upload_body = self.request_multipart(
                    "POST",
                    "/ruoyi-kb-management/documents",
                    self.admin_token,
                    {"knowledgeBaseId": str(knowledge_base_id)},
                    {"file": (filename, content, "text/plain")},
                )
                upload = json.loads(upload_body)
                self.assertEqual(200, upload["code"], f"{stage} 阶段上传应返回 200")
                document_id = upload["data"]["id"]
                self.assertEqual("PROCESSING", upload["data"]["status"])

                failed_doc = self.wait_for_status(knowledge_base_id, filename, "FAILED", timeout=180)
                self.assertIsNotNone(failed_doc, f"{stage} 阶段注入失败应在超时前进入 FAILED")
                self.assertEqual("FAILED", failed_doc["status"])
                self.assertIn(
                    "RAG 入库失败",
                    failed_doc["errorMessage"],
                    f"{stage} 阶段失败原因应保留可诊断信息",
                )

                stream = self.ask_question(
                    conversation_id,
                    f"{stage} 阶段有什么内容？",
                    f"sys05s-{stage}-{unique}",
                )
                self.assert_refused(stream)

                failed_doc_ids.append(document_id)

        self._write_flag("")
        return failed_doc_ids

    def _assert_successful_retry_is_retrievable(
        self, knowledge_base_id: int, unique: str, document_id: int
    ) -> None:
        """清空 flag 后重试失败文档，验证重试成功且内容可检索。

        重试前 flag 已清空，原文件内容有效，重试应成功进入 AVAILABLE。
        通过问答能检索到该文档唯一标记，证明成功重试后内容完整可检索。
        原子性（仅存在一套片段）由单元测试 ``test_index_clears_stale_vectors_before_retry``
        在应用层验证入库前 ``delete_by_document`` 清理残留向量。
        """
        _, list_body = self.request_json(
            "GET",
            f"/ruoyi-kb-management/documents?knowledgeBaseId={knowledge_base_id}",
            self.admin_token,
        )
        docs = json.loads(list_body)
        target = next(d for d in docs["data"] if d["id"] == document_id)
        filename = target["filename"]
        self.assertEqual("FAILED", target["status"], "重试前文档应为 FAILED")

        _, retry_body = self.request_json(
            "POST",
            f"/ruoyi-kb-management/documents/{document_id}/retry",
            self.admin_token,
        )
        retry = json.loads(retry_body)
        self.assertEqual(200, retry["code"], "清空 flag 后重试应成功发起")
        self.assertEqual("PROCESSING", retry["data"]["status"])
        self.assertEqual(document_id, retry["data"]["id"], "重试保留文档身份")
        self.assertEqual(filename, retry["data"]["filename"], "重试保留文件名")

        available = self.wait_for_status(knowledge_base_id, filename, "AVAILABLE", timeout=180)
        self.assertIsNotNone(available, "重试后应进入 AVAILABLE")
        self.assertEqual("AVAILABLE", available["status"])

        _, conv_body = self.request_json(
            "POST",
            "/ruoyi-kb-management/conversations",
            self.user_token,
            {"knowledgeBaseId": knowledge_base_id},
        )
        conv = json.loads(conv_body)
        self.assertEqual(200, conv["code"])
        conversation_id = conv["data"]["id"]

        distinctive = f"阶段失败标记 {unique}"
        stream = self.ask_question(
            conversation_id,
            distinctive,
            f"sys05s-retry-{unique}",
        )
        self.assertIn("event:started", stream)
        self.assertIn("event:completed", stream, "成功重试后应能完成回答")
        self.assertNotIn("event:refused", stream, "成功重试后不应拒答")
        self.assertIn(distinctive, stream, "应检索到重试成功文档的唯一内容")


if __name__ == "__main__":
    unittest.main()
