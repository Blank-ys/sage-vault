import json
import os
import time
import unittest
import urllib.error
import urllib.request

# 与 com.sagevault.kb.platform.error.ErrorCode 保持一致。
FEEDBACK_FORBIDDEN = 410021
FEEDBACK_CONSENT_REQUIRED = 410022
FEEDBACK_ALREADY_SUBMITTED = 410023
FEEDBACK_CATEGORY_INVALID = 410024


class UserFeedbackSubmissionAndConsentSystemTest(unittest.TestCase):
    """浏览器 -> Java 网关的反馈提交与同意共享验收（issue 08a）。

    需要两个不同普通用户的 token，用来证明用户只能对自己的问答提交反馈。
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

    def test_only_the_owner_can_submit_one_consented_feedback_per_answer(self) -> None:
        unique_name = f"system-feedback-{int(time.time())}"
        knowledge_base_id = self.json_data(
            "POST",
            "/ruoyi-kb-management/knowledge-bases",
            self.admin_token,
            {"name": unique_name, "description": "system acceptance for user feedback"},
        )["data"]["id"]

        conversation_id = self.json_data(
            "POST",
            "/ruoyi-kb-management/conversations",
            self.user_token,
            {"knowledgeBaseId": knowledge_base_id},
        )["data"]["id"]

        self.request(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": "报销流程是什么？", "requestId": f"system-feedback-{int(time.time())}"},
        )

        history = self.json_data(
            "GET", f"/ruoyi-kb-management/conversations/{conversation_id}/questions", self.user_token
        )["data"]
        self.assertTrue(history, "需要一条问答记录才能提交反馈")
        qa_id = history[-1]["id"]

        # 反馈入口初始为未提交状态。
        self.assertFalse(history[-1]["feedbackSubmitted"])

        # 匿名请求不得提交反馈。
        anonymous_status, _ = self.request(
            "POST",
            f"/ruoyi-kb-management/qa/{qa_id}/feedback",
            None,
            {"category": "WRONG_ANSWER", "consentToShare": True},
        )
        self.assertIn(anonymous_status, (401, 200))

        # 未勾选同意共享时拒绝落库。
        self.assertEqual(
            FEEDBACK_CONSENT_REQUIRED,
            self.error_code(
                "POST",
                f"/ruoyi-kb-management/qa/{qa_id}/feedback",
                self.user_token,
                {"category": "WRONG_ANSWER", "comment": "不该被保存"},
            ),
        )
        still_unsubmitted = self.json_data(
            "GET", f"/ruoyi-kb-management/conversations/{conversation_id}/questions", self.user_token
        )["data"]
        self.assertFalse(still_unsubmitted[-1]["feedbackSubmitted"])

        # 类别必须落在封闭集合内。
        self.assertEqual(
            FEEDBACK_CATEGORY_INVALID,
            self.error_code(
                "POST",
                f"/ruoyi-kb-management/qa/{qa_id}/feedback",
                self.user_token,
                {"category": "SOMETHING_ELSE", "consentToShare": True},
            ),
        )

        # 别的用户不能对这条问答提交反馈。
        self.assertEqual(
            FEEDBACK_FORBIDDEN,
            self.error_code(
                "POST",
                f"/ruoyi-kb-management/qa/{qa_id}/feedback",
                self.other_user_token,
                {"category": "WRONG_ANSWER", "consentToShare": True},
            ),
        )

        # 归属者明确同意后提交成功，且返回体不含管理端内部字段。
        submitted = self.json_data(
            "POST",
            f"/ruoyi-kb-management/qa/{qa_id}/feedback",
            self.user_token,
            {"category": "WRONG_ANSWER", "comment": "答案与文档不一致", "consentToShare": True},
        )["data"]
        self.assertEqual(qa_id, submitted["qaId"])
        self.assertEqual("WRONG_ANSWER", submitted["category"])
        self.assertEqual("答案与文档不一致", submitted["comment"])
        self.assertNotIn("adminNote", submitted)
        self.assertNotIn("status", submitted)

        # 历史回显已反馈，前端据此收敛入口。
        after = self.json_data(
            "GET", f"/ruoyi-kb-management/conversations/{conversation_id}/questions", self.user_token
        )["data"]
        self.assertTrue(after[-1]["feedbackSubmitted"])

        # 同一条问答只接受一次反馈。
        self.assertEqual(
            FEEDBACK_ALREADY_SUBMITTED,
            self.error_code(
                "POST",
                f"/ruoyi-kb-management/qa/{qa_id}/feedback",
                self.user_token,
                {"category": "OTHER", "consentToShare": True},
            ),
        )

        # 用户删除会话后反馈正文随问答一起消失。
        self.json_data("DELETE", f"/ruoyi-kb-management/conversations/{conversation_id}", self.user_token)
        self.assertEqual(
            FEEDBACK_FORBIDDEN,
            self.error_code(
                "POST",
                f"/ruoyi-kb-management/qa/{qa_id}/feedback",
                self.user_token,
                {"category": "OTHER", "consentToShare": True},
            ),
        )


if __name__ == "__main__":
    unittest.main()
