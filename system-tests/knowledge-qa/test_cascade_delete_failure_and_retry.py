import io
import json
import os
import time
import unittest
import urllib.error
import urllib.request
import uuid

# 与 com.sagevault.kb.platform.error.ErrorCode 保持一致。
# 活动记录被物理移除后，管理端 GET 该知识库返回 NOT_AVAILABLE(410002)。
KNOWLEDGE_BASE_NOT_FOUND = 410002
KNOWLEDGE_BASE_NOT_AVAILABLE = 410002
KNOWLEDGE_BASE_STATE_CONFLICT = 410029
KNOWLEDGE_BASE_DELETED = 410030

FAILURE_TIMEOUT_SECONDS = 180
CLEANUP_TIMEOUT_SECONDS = 180


class CascadeDeleteFailureAndRetrySystemTest(unittest.TestCase):
    """知识库级联删除的失败、幂等与安全性验收（issue 09b）。

    只走 Gateway 的公开接口。09b 的承诺是"失败不装成功、失败态只读、重试幂等、
    清理窗口内不接受新写入"，这些都必须能从知识管理员与普通用户看到的响应上观察出来。

    失败注入必须由环境提供：把知识库依赖的外部存储（MinIO 或 Milvus）置为不可用，
    让真实的清理链路失败，而不是在业务代码里埋测试开关。
    设置 SAGE_VAULT_CLEANUP_FAILURE_INJECTED=1 表示"当前环境下清理必定失败"。
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.base_url = os.environ["SAGE_VAULT_GATEWAY_URL"].rstrip("/")
        cls.admin_token = os.environ["SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN"]
        cls.user_token = os.environ["SAGE_VAULT_GENERAL_USER_TOKEN"]
        cls.failure_injected = os.environ.get("SAGE_VAULT_CLEANUP_FAILURE_INJECTED") == "1"

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

    def upload_document(self, knowledge_base_id: int, filename: str, content: bytes) -> tuple[int, int]:
        """通过网关上传文档，返回 (业务错误码或 200, 文档 ID 或 -1)。"""
        boundary = uuid.uuid4().hex
        buffer = io.BytesIO()
        buffer.write(f"--{boundary}\r\n".encode())
        buffer.write(b'Content-Disposition: form-data; name="knowledgeBaseId"\r\n\r\n')
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
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            payload = json.loads(error.read().decode())
        return payload["code"], payload.get("data", {}).get("id", -1) if payload["code"] == 200 else -1

    def status_of(self, knowledge_base_id: int) -> dict | None:
        """返回知识库当前可见状态；活动记录已被移除时返回 None。"""
        _, raw = self.request(
            "GET", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}", self.admin_token)
        payload = json.loads(raw)
        if payload["code"] == KNOWLEDGE_BASE_NOT_FOUND:
            return None
        self.assertEqual(200, payload["code"], raw)
        return payload["data"]

    def wait_for_delete_failed(self, knowledge_base_id: int) -> dict:
        """轮询直到知识库进入 DELETE_FAILED。清理成功说明失败注入没生效，测试无意义。"""
        deadline = time.time() + FAILURE_TIMEOUT_SECONDS
        last = None
        while time.time() < deadline:
            last = self.status_of(knowledge_base_id)
            if last is None:
                self.fail("清理意外成功：失败注入未生效，本用例无法验证失败路径")
            if last["status"] == "DELETE_FAILED":
                return last
            time.sleep(2)
        self.fail(f"知识库在 {FAILURE_TIMEOUT_SECONDS}s 内未进入 DELETE_FAILED，最后状态：{last}")

    def wait_until_removed(self, knowledge_base_id: int) -> None:
        deadline = time.time() + CLEANUP_TIMEOUT_SECONDS
        last = None
        while time.time() < deadline:
            last = self.status_of(knowledge_base_id)
            if last is None:
                return
            time.sleep(2)
        self.fail(f"知识库在 {CLEANUP_TIMEOUT_SECONDS}s 内未完成清理，最后状态：{last}")

    def create_knowledge_base(self, name: str) -> int:
        return self.json_data(
            "POST", "/ruoyi-kb-management/knowledge-bases", self.admin_token,
            {"name": name, "description": "cascade delete failure acceptance"},
        )["data"]["id"]

    def test_cleanup_failure_is_visible_and_never_reported_as_success(self) -> None:
        """清理失败必须暴露为 DELETE_FAILED 并带上可诊断的失败原因与阶段。"""
        if not self.failure_injected:
            self.skipTest("需要 SAGE_VAULT_CLEANUP_FAILURE_INJECTED=1（外部存储已置为不可用）")

        suffix = time.time_ns()
        knowledge_base_id = self.create_knowledge_base(f"system-09b-fail-{suffix}")
        code, _ = self.upload_document(knowledge_base_id, f"fail-{suffix}.md",
                                       "# 报销制度\n\n差旅报销需在返程后 10 个工作日内提交。\n".encode())
        self.assertEqual(200, code, "失败注入前必须能正常上传，否则无法产生待清理内容")

        self.assertEqual("DELETING", self.json_data(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
            self.admin_token)["data"]["status"])

        failed = self.wait_for_delete_failed(knowledge_base_id)
        # 失败原因必须落到响应上，且带上失败阶段，管理员才能判断该重试还是先修依赖
        self.assertTrue(failed["errorMessage"], "DELETE_FAILED 必须带上失败原因")
        self.assertRegex(failed["errorMessage"], r"向量清理|原文件清理|文档记录清理|未知阶段")

        # 失败态不得被当成可用：既不回到可用列表，也不接受新会话
        self.assertNotIn(knowledge_base_id, [item["id"] for item in self.json_data(
            "GET", "/ruoyi-kb-management/knowledge-bases/available", self.user_token)["data"]])
        self.assertIn(
            self.error_code("POST", "/ruoyi-kb-management/conversations", self.user_token,
                            {"knowledgeBaseId": knowledge_base_id}),
            (KNOWLEDGE_BASE_NOT_AVAILABLE, KNOWLEDGE_BASE_DELETED),
        )

    def test_delete_failed_knowledge_base_is_read_only_except_retry(self) -> None:
        """失败态只允许查看与重试删除：编辑会让一个正在消失的知识库看起来重新可用。"""
        if not self.failure_injected:
            self.skipTest("需要 SAGE_VAULT_CLEANUP_FAILURE_INJECTED=1（外部存储已置为不可用）")

        suffix = time.time_ns()
        knowledge_base_id = self.create_knowledge_base(f"system-09b-readonly-{suffix}")
        self.upload_document(knowledge_base_id, f"readonly-{suffix}.md",
                             "# 制度\n\n内容用于产生待清理的原文件与向量。\n".encode())
        self.request("DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
                     self.admin_token)
        failed = self.wait_for_delete_failed(knowledge_base_id)

        # 仍可查看：失败态必须保留诊断信息，不能一删了之
        self.assertEqual("DELETE_FAILED", failed["status"])
        self.assertIn(knowledge_base_id, [item["id"] for item in self.json_data(
            "GET", "/ruoyi-kb-management/knowledge-bases", self.admin_token)["data"]])

        # 不可编辑：改名等同于把失败态知识库重新拉回正常运营
        self.assertEqual(KNOWLEDGE_BASE_STATE_CONFLICT, self.error_code(
            "PUT", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}", self.admin_token,
            {"name": f"system-09b-renamed-{suffix}", "description": "失败态不该被编辑"}))
        self.assertEqual("DELETE_FAILED", self.status_of(knowledge_base_id)["status"])

        # 不接受新上传：清理窗口内的新写入会变成删除不掉的残留
        code, _ = self.upload_document(knowledge_base_id, f"blocked-{suffix}.md", b"# blocked\n")
        self.assertIn(code, (KNOWLEDGE_BASE_NOT_AVAILABLE, KNOWLEDGE_BASE_DELETED))

    def test_retry_after_failure_is_idempotent_and_eventually_completes(self) -> None:
        """恢复外部依赖后重试删除必须真正推进到完成，且不重复清理已删除内容。

        这条用例覆盖 09b 的完整验收链路：注入失败 → 拒绝新操作 → 恢复依赖 → 重试 → 清理完成。
        重试阶段需要外部存储恢复可用，因此由 SAGE_VAULT_CLEANUP_FAILURE_RECOVERED 显式声明。
        """
        if not self.failure_injected:
            self.skipTest("需要 SAGE_VAULT_CLEANUP_FAILURE_INJECTED=1（外部存储已置为不可用）")

        suffix = time.time_ns()
        knowledge_base_id = self.create_knowledge_base(f"system-09b-retry-{suffix}")
        self.upload_document(knowledge_base_id, f"retry-{suffix}.md",
                             "# 制度\n\n内容用于产生待清理的原文件与向量。\n".encode())
        self.request("DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
                     self.admin_token)
        self.wait_for_delete_failed(knowledge_base_id)

        # 失败态重试是幂等的：重复触发只会回到 DELETING，不会产生第二条删除流程
        first_retry = self.json_data(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
            self.admin_token)["data"]
        self.assertEqual("DELETING", first_retry["status"])
        self.assertEqual("DELETING", self.json_data(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
            self.admin_token)["data"]["status"])

        input_msg = ("请恢复被置为不可用的外部存储（MinIO / Milvus），"
                     "然后设置 SAGE_VAULT_CLEANUP_FAILURE_RECOVERED=1 重跑本用例以验证重试完成")
        if os.environ.get("SAGE_VAULT_CLEANUP_FAILURE_RECOVERED") != "1":
            self.skipTest(input_msg)

        # 依赖恢复后重试必须真正走完：清理预算被重置，残留重新进入清理流程
        self.json_data("DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
                       self.admin_token)
        self.wait_until_removed(knowledge_base_id)

        # 已删除后再次删除返回未找到，不会重新产生删除中记录，也不会重复清理
        self.assertEqual(KNOWLEDGE_BASE_NOT_FOUND, self.error_code(
            "DELETE", f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}", self.admin_token))

    def test_cleanup_never_touches_other_knowledge_bases(self) -> None:
        """清理窗口只作用于被删除的知识库，旁邻知识库必须完全不受影响。"""
        suffix = time.time_ns()
        victim_id = self.create_knowledge_base(f"system-09b-victim-{suffix}")
        bystander_id = self.create_knowledge_base(f"system-09b-bystander-{suffix}")
        code, _ = self.upload_document(bystander_id, f"bystander-{suffix}.md",
                                       "# 旁邻知识库\n\n这份内容不得被邻居的删除波及。\n".encode())
        self.assertEqual(200, code)

        self.json_data("DELETE", f"/ruoyi-kb-management/knowledge-bases/{victim_id}", self.admin_token)
        self.wait_until_removed(victim_id)

        # 旁邻知识库仍可用、仍可上传、仍可建会话，其文档没有被连带删除
        bystander = self.status_of(bystander_id)
        self.assertIsNotNone(bystander)
        self.assertEqual("AVAILABLE", bystander["status"])
        self.assertTrue(self.json_data(
            "GET", f"/ruoyi-kb-management/documents?knowledgeBaseId={bystander_id}",
            self.admin_token)["data"], "旁邻知识库的文档不得被邻居的级联删除清空")
        self.assertEqual(200, self.upload_document(
            bystander_id, f"bystander-after-{suffix}.md", b"# still writable\n")[0])
        self.json_data("POST", "/ruoyi-kb-management/conversations", self.user_token,
                       {"knowledgeBaseId": bystander_id})

        self.json_data("DELETE", f"/ruoyi-kb-management/knowledge-bases/{bystander_id}",
                       self.admin_token)
