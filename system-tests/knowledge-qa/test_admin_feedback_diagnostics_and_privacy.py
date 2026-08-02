import json
import os
import time
import unittest
import urllib.error
import urllib.request

# 与 com.sagevault.kb.platform.error.ErrorCode 保持一致。
FEEDBACK_NOT_FOUND = 410027
FORBIDDEN = 403


class AdminFeedbackDiagnosticsAndPrivacySystemTest(unittest.TestCase):
    """浏览器 -> Java 网关的管理员反馈处理验收（issue 08b）。

    覆盖三件事：管理员只能看到被反馈的问答、处理状态可闭环、用户删除后正文消失。
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.base_url = os.environ["SAGE_VAULT_GATEWAY_URL"].rstrip("/")
        cls.admin_token = os.environ["SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN"]
        cls.user_token = os.environ["SAGE_VAULT_GENERAL_USER_TOKEN"]
        cls.other_user_token = os.environ["SAGE_VAULT_SECOND_USER_TOKEN"]

    def request(
        self, method: str, path: str, token: str | None, body: dict[str, object] | None = None
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

    def json_data(
        self, method: str, path: str, token: str | None, body: dict[str, object] | None = None
    ) -> dict:
        _, raw = self.request(method, path, token, body)
        payload = json.loads(raw)
        self.assertEqual(200, payload["code"], raw)
        return payload

    def error_code(
        self, method: str, path: str, token: str | None, body: dict[str, object] | None = None
    ) -> int:
        _, raw = self.request(method, path, token, body)
        return json.loads(raw)["code"]

    def answered_question(self, conversation_id: int, question: str) -> int:
        self.request(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": question, "requestId": f"system-admin-feedback-{time.time_ns()}"},
        )
        history = self.json_data(
            "GET",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
        )["data"]
        self.assertTrue(history, "需要一条问答记录")
        return history[-1]["id"]

    def find_in_queue(self, status: str | None, feedback_id: int) -> dict | None:
        query = f"?pageSize=100&status={status}" if status else "?pageSize=100"
        page = self.json_data(
            "GET", f"/ruoyi-kb-management/admin/feedback{query}", self.admin_token
        )["data"]
        return next((item for item in page["items"] if item["id"] == feedback_id), None)

    def test_admin_only_sees_reported_answers_and_can_close_the_loop(self) -> None:
        unique_name = f"system-admin-feedback-{int(time.time())}"
        knowledge_base_id = self.json_data(
            "POST",
            "/ruoyi-kb-management/knowledge-bases",
            self.admin_token,
            {"name": unique_name, "description": "system acceptance for admin feedback"},
        )["data"]["id"]

        conversation_id = self.json_data(
            "POST",
            "/ruoyi-kb-management/conversations",
            self.user_token,
            {"knowledgeBaseId": knowledge_base_id},
        )["data"]["id"]

        reported_qa_id = self.answered_question(conversation_id, "报销流程是什么？")
        unreported_qa_id = self.answered_question(conversation_id, "这条不提交反馈，管理员不该看到")

        feedback = self.json_data(
            "POST",
            f"/ruoyi-kb-management/qa/{reported_qa_id}/feedback",
            self.user_token,
            {"category": "WRONG_ANSWER", "comment": "答案与文档不一致", "consentToShare": True},
        )["data"]
        feedback_id = feedback["id"]

        # 普通用户即使已登录也不能进入管理端队列。
        self.assertEqual(
            FORBIDDEN,
            self.error_code("GET", "/ruoyi-kb-management/admin/feedback", self.user_token),
        )
        self.assertEqual(
            FORBIDDEN,
            self.error_code(
                "GET", f"/ruoyi-kb-management/admin/feedback/{feedback_id}", self.other_user_token
            ),
        )
        self.assertEqual(
            FORBIDDEN,
            self.error_code(
                "PUT",
                f"/ruoyi-kb-management/admin/feedback/{feedback_id}/status",
                self.user_token,
                {"status": "RESOLVED"},
            ),
        )

        # 新反馈进入待处理队列。
        queued = self.find_in_queue("PENDING", feedback_id)
        self.assertIsNotNone(queued, "新反馈应出现在待处理队列")
        self.assertEqual("PENDING", queued["status"])

        # 详情返回用户已授权共享的问答正文与请求 ID。
        detail = self.json_data(
            "GET", f"/ruoyi-kb-management/admin/feedback/{feedback_id}", self.admin_token
        )["data"]
        self.assertEqual(reported_qa_id, detail["qaId"])
        self.assertEqual("报销流程是什么？", detail["question"])
        self.assertIn("answer", detail)
        self.assertTrue(detail["requestId"], "请求 ID 用于串联同一次问答")

        # 未提交反馈的问答没有管理端入口：用问答 ID 当反馈 ID 也取不到正文。
        unreported_lookup_code = self.error_code(
            "GET", f"/ruoyi-kb-management/admin/feedback/{unreported_qa_id}", self.admin_token
        )
        if unreported_lookup_code == 200:
            leaked = self.json_data(
                "GET",
                f"/ruoyi-kb-management/admin/feedback/{unreported_qa_id}",
                self.admin_token,
            )["data"]
            self.assertNotEqual(
                unreported_qa_id,
                leaked["qaId"],
                "未提交反馈的问答正文不得出现在管理端",
            )
        else:
            self.assertEqual(FEEDBACK_NOT_FOUND, unreported_lookup_code)

        # 整个队列里都不该出现未反馈的那条问答。
        whole_queue = self.json_data(
            "GET", "/ruoyi-kb-management/admin/feedback?pageSize=100", self.admin_token
        )["data"]
        self.assertNotIn(
            unreported_qa_id,
            [item["qaId"] for item in whole_queue["items"]],
            "未提交反馈的问答不得进入管理端队列",
        )

        # 处理后带上内部备注，并移出待处理队列。
        resolved = self.json_data(
            "PUT",
            f"/ruoyi-kb-management/admin/feedback/{feedback_id}/status",
            self.admin_token,
            {"status": "RESOLVED", "adminNote": "已核实并修正文档"},
        )["data"]
        self.assertEqual("RESOLVED", resolved["status"])
        self.assertEqual("已核实并修正文档", resolved["adminNote"])
        self.assertIsNone(self.find_in_queue("PENDING", feedback_id))
        self.assertIsNotNone(self.find_in_queue("RESOLVED", feedback_id))

        # 内部备注不回流给提交反馈的用户。
        user_history = self.json_data(
            "GET",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
        )["data"]
        self.assertNotIn("已核实并修正文档", json.dumps(user_history, ensure_ascii=False))

        # 已处理的反馈可以重新打开。
        reopened = self.json_data(
            "PUT",
            f"/ruoyi-kb-management/admin/feedback/{feedback_id}/status",
            self.admin_token,
            {"status": "PENDING", "adminNote": "复核后重开"},
        )["data"]
        self.assertEqual("PENDING", reopened["status"])

        # 用户删除会话后，管理端不再保留其问答正文。
        self.json_data(
            "DELETE", f"/ruoyi-kb-management/conversations/{conversation_id}", self.user_token
        )
        self.assertEqual(
            FEEDBACK_NOT_FOUND,
            self.error_code(
                "GET", f"/ruoyi-kb-management/admin/feedback/{feedback_id}", self.admin_token
            ),
        )
        self.assertIsNone(self.find_in_queue(None, feedback_id))


if __name__ == "__main__":
    unittest.main()
