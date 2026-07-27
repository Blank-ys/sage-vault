import json
import os
import time
import unittest
import urllib.error
import urllib.request


class EmptyKnowledgeBaseSystemTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.base_url = os.environ["SAGE_VAULT_GATEWAY_URL"].rstrip("/")
        cls.admin_token = os.environ["SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN"]
        cls.user_token = os.environ["SAGE_VAULT_GENERAL_USER_TOKEN"]

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
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def test_authenticated_browser_to_java_to_python_empty_kb_flow(self) -> None:
        # RuoYi gateway rejects anonymous requests with HTTP 200 and business code 401.
        anonymous_status, anonymous_body = self.request("GET", "/ruoyi-kb-management/knowledge-bases/available", None)
        self.assertEqual(200, anonymous_status)
        self.assertEqual(401, json.loads(anonymous_body)["code"])

        unique_name = f"system-empty-{int(time.time())}"
        _, created_body = self.request("POST", "/ruoyi-kb-management/knowledge-bases", self.admin_token, {
            "name": unique_name,
            "description": "system acceptance",
        })
        created = json.loads(created_body)
        self.assertEqual(200, created["code"])
        knowledge_base_id = created["data"]["id"]

        _, forbidden_management = self.request(
            "GET", "/ruoyi-kb-management/knowledge-bases", self.user_token
        )
        self.assertEqual(403, json.loads(forbidden_management)["code"])

        _, duplicate_body = self.request("POST", "/ruoyi-kb-management/knowledge-bases", self.admin_token, {
            "name": unique_name.upper(),
            "description": "case-insensitive duplicate",
        })
        self.assertEqual(410001, json.loads(duplicate_body)["code"])

        _, updated_body = self.request(
            "PUT",
            f"/ruoyi-kb-management/knowledge-bases/{knowledge_base_id}",
            self.admin_token,
            {"name": unique_name + "-updated", "description": "updated"},
        )
        self.assertEqual(200, json.loads(updated_body)["code"])

        _, available_body = self.request("GET", "/ruoyi-kb-management/knowledge-bases/available", self.user_token)
        available = json.loads(available_body)
        self.assertIn(knowledge_base_id, [item["id"] for item in available["data"]])

        _, conversation_body = self.request("POST", "/ruoyi-kb-management/conversations", self.user_token, {
            "knowledgeBaseId": knowledge_base_id,
        })
        conversation = json.loads(conversation_body)
        self.assertEqual(200, conversation["code"])

        _, stream = self.request(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation['data']['id']}/questions",
            self.user_token,
            {"question": "这里有什么内容？", "requestId": f"system-{int(time.time())}"},
        )
        self.assertLess(stream.index("event:started"), stream.index("event:refused"))
        self.assertIn("该知识库暂无可用文档", stream)


if __name__ == "__main__":
    unittest.main()
