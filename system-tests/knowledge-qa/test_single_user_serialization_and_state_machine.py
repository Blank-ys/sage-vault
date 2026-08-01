import json
import os
import threading
import time
import unittest
import urllib.error
import urllib.request

# 与 com.sagevault.kb.platform.error.ErrorCode 保持一致。
CONVERSATION_FORBIDDEN = 410004
CONVERSATION_CONCURRENCY_CONFLICT = 410016
ANSWER_NOT_READY = 410017

# 真实环境若已灌入可检索文档（Milvus 有向量、KB 有 AVAILABLE 文档），置为 true，
# 使「同用户串行拒绝」硬断言可稳定触发 STARTED 竞争窗口；否则该测试跳过并交由
# Java 单元 + live MySQL 集成测试作为该事项的权威证据。
HAS_RETRIEVABLE_DOCS = os.environ.get("SAGE_VAULT_HAS_RETRIEVABLE_DOCS", "") == "1"


class SingleUserSerializationAndStateMachineSystemTest(unittest.TestCase):
    """浏览器 -> 网关 -> Java -> Python 的单用户串行化与回答状态机验收（issue 07b）。

    覆盖：
      - 不同用户可并发各自提问，互不阻塞；
      - 回答状态机端点 GET /{id}/answers/{generationId} 正确反映 REFUSED 终态；
      - 拒答持久化为 REFUSED 终态，且不计入"进行中"闸门（可再次提问）；
      - 另一用户读取该回答状态被所有权拒绝（410004），不泄露状态；
      - [需真实可检索文档] 同一用户同一会话进行中再次提问被
        CONVERSATION_CONCURRENCY_CONFLICT 拒绝。
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.base_url = os.environ["SAGE_VAULT_GATEWAY_URL"].rstrip("/")
        cls.admin_token = os.environ["SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN"]
        cls.user_token = os.environ["SAGE_VAULT_GENERAL_USER_TOKEN"]
        cls.other_user_token = os.environ["SAGE_VAULT_SECOND_USER_TOKEN"]

    def request(self, method: str, path: str, token: str | None, body: dict | None = None) -> tuple[int, str]:
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

    def json_code(self, method: str, path: str, token: str | None, body: dict | None = None) -> int:
        status, raw = self.request(method, path, token, body)
        self.assertEqual(200, status, raw)
        return json.loads(raw)["code"]

    def create_knowledge_base(self, suffix: str) -> int:
        created = self.request("POST", "/ruoyi-kb-management/knowledge-bases", self.admin_token, {
            "name": f"system-07b-{suffix}",
            "description": "single-user serialization system acceptance",
        })
        self.assertEqual(200, created[0])
        return json.loads(created[1])["data"]["id"]

    def create_conversation(self, token: str, knowledge_base_id: int) -> int:
        status, raw = self.request("POST", "/ruoyi-kb-management/conversations", token, {
            "knowledgeBaseId": knowledge_base_id,
        })
        self.assertEqual(200, status, raw)
        return json.loads(raw)["data"]["id"]

    @staticmethod
    def _generation_id_from_stream(raw: str) -> str | None:
        for block in raw.split("\n\n"):
            for line in block.splitlines():
                if line.startswith("data:") and "generationId" in line:
                    return json.loads(line[len("data:"):].strip())["generationId"]
        return None

    def stream_events(self, token: str, conversation_id: int, question: str, request_id: str) -> str:
        status, raw = self.request(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            token,
            {"question": question, "requestId": request_id},
        )
        self.assertEqual(200, status, raw)
        return raw

    def test_different_users_can_concurrently_ask(self) -> None:
        knowledge_base_id = self.create_knowledge_base(f"concurrent-{int(time.time())}")
        conversation_a = self.create_conversation(self.user_token, knowledge_base_id)
        conversation_b = self.create_conversation(self.other_user_token, knowledge_base_id)

        raw_a = self.stream_events(self.user_token, conversation_a, "user A question", f"conc-a-{int(time.time())}")
        raw_b = self.stream_events(self.other_user_token, conversation_b, "user B question", f"conc-b-{int(time.time())}")

        # 两个用户都成功拿到 started 事件，互不阻塞。
        self.assertIn("event:started", raw_a, raw_a)
        self.assertIn("event:started", raw_b, raw_b)

    def test_state_machine_endpoint_reports_refused_terminal(self) -> None:
        knowledge_base_id = self.create_knowledge_base(f"state-{int(time.time())}")
        conversation_id = self.create_conversation(self.user_token, knowledge_base_id)

        raw = self.stream_events(self.user_token, conversation_id, "no docs should refuse", f"state-{int(time.time())}")
        self.assertIn("event:refused", raw, raw)

        generation_id = self._generation_id_from_stream(raw)
        self.assertIsNotNone(generation_id)

        # 状态端点：拒答是终态，ready=true 且 status=REFUSED。
        status, body = self.request(
            "GET",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}",
            self.user_token,
        )
        self.assertEqual(200, status, body)
        snapshot = json.loads(body)["data"]
        self.assertEqual(generation_id, snapshot["generationId"])
        self.assertTrue(snapshot["ready"], "拒答为终态，应可读取")
        self.assertEqual("REFUSED", snapshot["status"], "拒答应持久化为 REFUSED 终态")

        # 拒答不计入"进行中"闸门：该会话可再次提问（闸门已释放）。
        retry = self.stream_events(self.user_token, conversation_id, "retry should be allowed", f"state-retry-{int(time.time())}")
        self.assertIn("event:started", retry, retry)

    def test_state_endpoint_rejects_cross_user_answer(self) -> None:
        knowledge_base_id = self.create_knowledge_base(f"cross-{int(time.time())}")
        conversation_id = self.create_conversation(self.user_token, knowledge_base_id)

        raw = self.stream_events(self.user_token, conversation_id, "owner question", f"cross-{int(time.time())}")
        generation_id = self._generation_id_from_stream(raw)
        self.assertIsNotNone(generation_id)

        # 另一用户读取该回答状态，必须被所有权拒绝（410004），而非泄露状态。
        code = self.json_code(
            "GET",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}",
            self.other_user_token,
        )
        self.assertEqual(CONVERSATION_FORBIDDEN, code)

    @unittest.skipUnless(HAS_RETRIEVABLE_DOCS,
                         "需真实可检索文档才能稳定制造 STARTED 竞争窗口；"
                         "无文档环境下拒答秒级终态无法触发 CONVERSATION_CONCURRENCY_CONFLICT，"
                         "该事项由 Java 单测与 live MySQL 集成测试确定性验证")
    def test_same_user_second_question_while_in_progress_is_rejected(self) -> None:
        knowledge_base_id = self.create_knowledge_base(f"serial-{int(time.time())}")
        conversation_id = self.create_conversation(self.user_token, knowledge_base_id)

        started_generation_id: list[str | None] = [None]
        conflict_code: list[int | None] = [None]
        first_stream_raw: list[str] = [""]
        gate = threading.Event()

        def first_question() -> None:
            # 保持连接打开：只读取到 started 事件，不消费后续，使 STARTED 记录持续"进行中"。
            headers = {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.user_token}",
            }
            req = urllib.request.Request(
                self.base_url + f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
                method="POST",
                headers=headers,
                data=json.dumps(
                    {"question": "please keep generating", "requestId": f"serial-first-{int(time.time())}"},
                    ensure_ascii=False,
                ).encode(),
            )
            with urllib.request.urlopen(req, timeout=60) as response:
                for line in response:
                    text = line.decode()
                    first_stream_raw[0] += text
                    if text.startswith("event:started"):
                        data_line = next(response).decode()
                        started_generation_id[0] = json.loads(data_line[len("data:"):].strip())["generationId"]
                        gate.set()  # 已落库 STARTED，放行第二个请求
                        time.sleep(3)  # 故意保持连接，使闸门在第二个请求期间仍处于 pending
                        break

        def second_question() -> None:
            gate.wait(timeout=30)
            conflict_code[0] = self.json_code(
                "POST",
                f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
                self.user_token,
                {"question": "second should be rejected", "requestId": f"serial-second-{int(time.time())}"},
            )

        first = threading.Thread(target=first_question, daemon=True)
        second = threading.Thread(target=second_question, daemon=True)
        first.start()
        second.start()
        first.join(timeout=70)
        second.join(timeout=10)

        self.assertIsNotNone(started_generation_id[0], f"未收到 started 事件: {first_stream_raw[0]}")
        self.assertEqual(CONVERSATION_CONCURRENCY_CONFLICT, conflict_code[0],
                         "同一会话进行中的第二次提问必须被 410016 拒绝")


if __name__ == "__main__":
    unittest.main()
