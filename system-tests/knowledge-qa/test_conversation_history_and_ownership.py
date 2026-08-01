import json
import os
import time
import unittest
import urllib.error
import urllib.request

# 与 com.sagevault.kb.platform.error.ErrorCode 保持一致。
CONVERSATION_NOT_FOUND = 410003
CONVERSATION_FORBIDDEN = 410004


class ConversationHistoryAndOwnershipSystemTest(unittest.TestCase):
    """浏览器 -> Java 网关的会话历史与所有权验收。

    需要两个不同普通用户的 token，用来证明会话不会跨用户可见。
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.base_url = os.environ["SAGE_VAULT_GATEWAY_URL"].rstrip("/")
        cls.admin_token = os.environ["SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN"]
        cls.user_token = os.environ["SAGE_VAULT_GENERAL_USER_TOKEN"]
        cls.other_user_token = os.environ["SAGE_VAULT_SECOND_USER_TOKEN"]

    def request(self, method: str, path: str, token: str | None, body: dict[str, object] | None = None) -> tuple[int, str]:
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

    def json_data(self, method: str, path: str, token: str | None, body: dict[str, object] | None = None) -> dict:
        _, raw = self.request(method, path, token, body)
        payload = json.loads(raw)
        self.assertEqual(200, payload["code"], raw)
        return payload

    def test_history_is_owned_per_user_and_never_feeds_retrieval(self) -> None:
        unique_name = f"system-history-{int(time.time())}"
        knowledge_base_id = self.json_data("POST", "/ruoyi-kb-management/knowledge-bases", self.admin_token, {
            "name": unique_name,
            "description": "system acceptance for conversation history",
        })["data"]["id"]

        conversation_id = self.json_data("POST", "/ruoyi-kb-management/conversations", self.user_token, {
            "knowledgeBaseId": knowledge_base_id,
        })["data"]["id"]

        first_question = "第一个问题：报销流程是什么？"
        self.request(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": first_question, "requestId": f"system-history-1-{int(time.time())}"},
        )

        # 首个问题成为默认标题。
        conversation = self.json_data("GET", f"/ruoyi-kb-management/conversations/{conversation_id}", self.user_token)
        self.assertEqual(first_question, conversation["data"]["title"])

        # 标题可改，且后续提问不覆盖已改标题。
        self.json_data("PUT", f"/ruoyi-kb-management/conversations/{conversation_id}/title", self.user_token, {
            "title": "报销相关",
        })
        second_question = "第二个问题：它需要几天？"
        self.request(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            self.user_token,
            {"question": second_question, "requestId": f"system-history-2-{int(time.time())}"},
        )
        self.assertEqual(
            "报销相关",
            self.json_data("GET", f"/ruoyi-kb-management/conversations/{conversation_id}", self.user_token)["data"]["title"],
        )

        # 一个会话保存多条按时间展示的独立问答记录。
        history = self.json_data(
            "GET", f"/ruoyi-kb-management/conversations/{conversation_id}/questions", self.user_token
        )["data"]
        self.assertEqual([first_question, second_question], [record["question"] for record in history])

        # 历史不参与检索或模型上下文：第二个问题的回答不得复用第一个问题的正文。
        self.assertNotIn(first_question, history[1]["answer"] or "")

        # 所有权隔离：另一个用户看不到、读不到、改不了也删不掉这个会话。
        other_list = self.json_data("GET", "/ruoyi-kb-management/conversations", self.other_user_token)["data"]
        self.assertNotIn(conversation_id, [item["id"] for item in other_list])

        for method, path, body in (
            ("GET", f"/ruoyi-kb-management/conversations/{conversation_id}", None),
            ("GET", f"/ruoyi-kb-management/conversations/{conversation_id}/questions", None),
            ("PUT", f"/ruoyi-kb-management/conversations/{conversation_id}/title", {"title": "越权改名"}),
            ("DELETE", f"/ruoyi-kb-management/conversations/{conversation_id}", None),
        ):
            _, raw = self.request(method, path, self.other_user_token, body)
            self.assertEqual(CONVERSATION_FORBIDDEN, json.loads(raw)["code"], f"{method} {path} -> {raw}")

        # 删除会话后，会话与其问答正文都不再可读。
        self.json_data("DELETE", f"/ruoyi-kb-management/conversations/{conversation_id}", self.user_token)
        _, deleted = self.request("GET", f"/ruoyi-kb-management/conversations/{conversation_id}", self.user_token)
        self.assertEqual(CONVERSATION_NOT_FOUND, json.loads(deleted)["code"])
        owned = self.json_data("GET", "/ruoyi-kb-management/conversations", self.user_token)["data"]
        self.assertNotIn(conversation_id, [item["id"] for item in owned])


if __name__ == "__main__":
    unittest.main()
