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


class RetryAndAtomicPublicationSystemTest(unittest.TestCase):
    """05 系统验收：注入解析失败，证明失败/重试期间没有部分内容被检索。

    黑盒路径：浏览器 -> Gateway -> Java kb-management -> Python RAG -> Milvus。
    不直连 Python、MinIO 或数据库，只通过 Gateway 观察 HTTP 行为。
    """

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


if __name__ == "__main__":
    unittest.main()
