import io
import json
import os
import time
import unittest
import urllib.error
import urllib.request
import uuid

# 与 com.sagevault.kb.platform.error.ErrorCode 保持一致。
# 注意：删除后活动记录被物理移除，管理端 GET 该知识库返回 NOT_AVAILABLE(410002)，不存在 410001。
KNOWLEDGE_BASE_NOT_FOUND = 410002  # 记录已移除后管理端查询得到 NOT_AVAILABLE
KNOWLEDGE_BASE_NOT_AVAILABLE = 410002
KNOWLEDGE_BASE_DELETED = 410030

CLEANUP_TIMEOUT_SECONDS = 120


class CascadeDeleteKnowledgeBaseSystemTest(unittest.TestCase):
    """浏览器 -> Java 网关的知识库级联删除验收（issue 09a）。

    只走 Gateway 的公开接口，不直连 Python、MinIO、Milvus 或业务表：
    删除承诺必须能从知识管理员与普通用户各自看到的响应上观察出来。
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.base_url = os.environ["SAGE_VAULT_GATEWAY_URL"].rstrip("/")
        cls.admin_token = os.environ["SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN"]
        cls.user_token = os.environ["SAGE_VAULT_GENERAL_USER_TOKEN"]

    def request(self, method: str, path: str, token: str | None,
                body: dict[str, object] | None = None) -> tuple[int, str]:
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

    def json_data(self, method: str, path: str, token: str | None,
                  body: dict[str, object] | None = None) -> dict:
        _, raw = self.request(method, path, token, body)
        payload = json.loads(raw)
        self.assertEqual(200, payload["code"], raw)
        return payload

    def error_code(self, method: str, path: str, token: str | None,
                   body: dict[str, object] | None = None) -> int:
        _, raw = self.request(method, path, token, body)
        return json.loads(raw)["code"]

    def upload_document(self, knowledge_base_id: int, filename: str, content: bytes) -> int:
        """通过网关上传一个真实文档，用来产生 MinIO 原文件与向量。"""
        boundary = uuid.uuid4().hex
        buffer = io.BytesIO()
        buffer.write(f"--{boundary}\r\n".encode())
        buffer.write(
            f'Content-Disposition: form-data; name="knowledgeBaseId"\r\n\r\n'.encode())
        buffer.write(str(knowledge_base_id).encode())
        buffer.write(f"\r\n--{boundary}\r\n".encode())
        buffer.write(
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode())
        buffer.write(b"Content-Type: text/markdown\r\n\r\n")
        buffer.write(content)
        buffer.write(f"\r\n--{boundary}--\r\n".encode())
        request = urllib.request.Request(
            f"{self.base_url}/ruoyi-kb-management/documents",
            method="POST",
            headers={
                "Authorization": f"Bearer {self.admin_token}",
                "Content-Type": f"multipart/form-data; boundary={boundary}",
            },
            data=buffer.getvalue(),
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = json.loads(response.read().decode())
        self.assertEqual(200, payload["code"], payload)
        return payload["data"]["id"]

    def wait_until_removed(self, knowledge_base_id: int) -> None:
        """轮询直到知识库活动记录被移除；失败或超时都要暴露最后一次可见状态。"""
        deadline = time.time() + CLEANUP_TIMEOUT_SECONDS
        last = None
        while time.time() < deadline:
            _, raw = self.request(
                "GET", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}", self.admin_token)
            payload = json.loads(raw)
            if payload["code"] == KNOWLEDGE_BASE_NOT_FOUND:
                return
            # 删除进行中，要么仍在 200 可见，要么拿到业务错误码（已被移除）
            if payload["code"] != 200:
                self.fail(f"级联清理期间出现非预期错误码：{raw}")
            last = payload["data"]
            self.assertNotEqual("DELETE_FAILED", last["status"],
                                f"级联清理失败：{last.get('errorMessage')}")
            time.sleep(2)
        self.fail(f"知识库在 {CLEANUP_TIMEOUT_SECONDS}s 内未完成级联删除，最后状态：{last}")

    def test_cascade_delete_removes_content_and_keeps_history_readable(self) -> None:
        suffix = int(time.time())
        knowledge_base_id = self.json_data(
            "POST", "/ruoyi-kb-management/knowledge-bases", self.admin_token,
            {"name": f"system-09a-{suffix}", "description": "cascade delete acceptance"},
        )["data"]["id"]

        # 真实内容：一个文档意味着一份 MinIO 原文件与一批向量
        self.upload_document(knowledge_base_id, f"cascade-{suffix}.md",
                             "# 报销制度\n\n差旅报销需在返程后 10 个工作日内提交。\n".encode())

        # 普通用户在删除前建立会话并提问，形成必须被保留的历史
        conversation_id = self.json_data(
            "POST", "/ruoyi-kb-management/conversations", self.user_token,
            {"knowledgeBaseId": knowledge_base_id})["data"]["id"]
        question = "差旅报销要多久提交？"
        self.request("POST", f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
                     self.user_token, {"question": question, "requestId": f"system-09a-{suffix}"})

        # 删除请求返回即代表知识库进入删除中
        deleted = self.json_data(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}", self.admin_token)["data"]
        self.assertEqual("DELETING", deleted["status"])

        # 删除中立即拒绝新上传、新会话与继续提问，且不再出现在可选知识库列表
        available = self.json_data(
            "GET", "/ruoyi-kb-management/knowledge-bases/available", self.user_token)["data"]
        self.assertNotIn(knowledge_base_id, [item["id"] for item in available])
        self.assertIn(
            self.error_code("POST", "/ruoyi-kb-management/conversations", self.user_token,
                            {"knowledgeBaseId": knowledge_base_id}),
            (KNOWLEDGE_BASE_NOT_AVAILABLE, KNOWLEDGE_BASE_DELETED),
        )
        self.assertIn(
            self.error_code("POST", f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
                            self.user_token,
                            {"question": "删除中还能问吗", "requestId": f"system-09a-deleting-{suffix}"}),
            (KNOWLEDGE_BASE_NOT_AVAILABLE, KNOWLEDGE_BASE_DELETED),
        )

        # 重复删除是幂等的，不会把知识库推入异常状态
        self.assertEqual("DELETING", self.json_data(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
            self.admin_token)["data"]["status"])

        # 清理完成后活动记录消失，管理端与用户端都查不到
        self.wait_until_removed(knowledge_base_id)
        self.assertEqual(KNOWLEDGE_BASE_NOT_FOUND, self.error_code(
            "GET", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}", self.admin_token))
        self.assertNotIn(knowledge_base_id, [item["id"] for item in self.json_data(
            "GET", "/ruoyi-kb-management/knowledge-bases", self.admin_token)["data"]])

        # 历史会话与问答仍可读，并被标记为"知识库已删除"
        conversation = self.json_data(
            "GET", f"/ruoyi-kb-management/conversations/{conversation_id}", self.user_token)["data"]
        self.assertTrue(conversation["knowledgeBaseDeleted"])
        history = self.json_data(
            "GET", f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token)["data"]
        self.assertIn(question, [record["question"] for record in history])
        self.assertTrue(any(item["id"] == conversation_id and item["knowledgeBaseDeleted"]
                            for item in self.json_data(
                                "GET", "/ruoyi-kb-management/conversations", self.user_token)["data"]))

        # 知识库已删除后不能继续提问
        self.assertEqual(KNOWLEDGE_BASE_DELETED, self.error_code(
            "POST", f"/ruoyi-kb-management/conversations/{conversation_id}/questions", self.user_token,
            {"question": "删除后还能问吗", "requestId": f"system-09a-deleted-{suffix}"}))

    def test_delete_on_empty_knowledge_base_completes_and_is_idempotent(self) -> None:
        suffix = int(time.time())
        knowledge_base_id = self.json_data(
            "POST", "/ruoyi-kb-management/knowledge-bases", self.admin_token,
            {"name": f"system-09a-empty-{suffix}", "description": "cascade delete on empty kb"},
        )["data"]["id"]

        self.assertEqual("DELETING", self.json_data(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
            self.admin_token)["data"]["status"])
        self.wait_until_removed(knowledge_base_id)

        # 对已删除知识库重复删除返回未找到，不产生新的删除中记录
        self.assertEqual(KNOWLEDGE_BASE_NOT_FOUND, self.error_code(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}", self.admin_token))
