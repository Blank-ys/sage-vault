import json
import os
import threading
import time
import unittest
import urllib.error
import urllib.request

# 与 com.sagevault.kb.platform.error.ErrorCode 保持一致。
CONVERSATION_FORBIDDEN = 410004
ANSWER_NOT_READY = 410017
ANSWER_NOT_STOPPABLE = 410020

# 真实环境若已灌入可检索文档（Milvus 有向量、KB 有 AVAILABLE 文档），置为 1，
# 使「生成中途停止」硬断言可稳定触发 STARTED 窗口；否则相关用例跳过并交由
# Java 单测、live MySQL 集成测试与 Python 契约测试作为该事项的权威证据。
HAS_RETRIEVABLE_DOCS = os.environ.get("SAGE_VAULT_HAS_RETRIEVABLE_DOCS", "") == "1"
RETRIEVABLE_KB_ID = os.environ.get("SAGE_VAULT_RETRIEVABLE_KB_ID", "")


class StreamStopAndBestEffortCancelSystemTest(unittest.TestCase):
    """浏览器 -> 网关 -> Java -> Python 的流式停止与尽力取消验收（issue 07c）。

    覆盖：
      - 停止是显式业务命令：停止后回答终态为 STOPPED，且保留已生成的残缺正文；
      - 已终态回答再次停止被 ANSWER_NOT_STOPPABLE 拒绝，终态不被改写；
      - 另一用户无法停止他人回答（410004），不泄露状态也不影响终态；
      - 停止后该会话闸门释放，可立即再次提问；
      - 仅断开连接不等于业务取消：终态是 UNFINISHED 而不是 STOPPED。
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
            "name": f"system-07c-{suffix}",
            "description": "stream stop and best-effort cancel system acceptance",
        })
        self.assertEqual(200, created[0], created[1])
        return json.loads(created[1])["data"]["id"]

    def create_conversation(self, token: str, knowledge_base_id: int) -> int:
        status, raw = self.request("POST", "/ruoyi-kb-management/conversations", token, {
            "knowledgeBaseId": knowledge_base_id,
        })
        self.assertEqual(200, status, raw)
        return json.loads(raw)["data"]["id"]

    def answer_state(self, token: str, conversation_id: int, generation_id: str) -> dict:
        status, raw = self.request(
            "GET",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}",
            token,
        )
        self.assertEqual(200, status, raw)
        return json.loads(raw)["data"]

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

    def _stream_until_started(self, conversation_id: int, request_id: str, question: str,
                              hold_seconds: float) -> dict:
        """打开一路 SSE 并在收到 started 后保持连接，返回观察到的事件与 generationId。"""
        observed: dict = {"generationId": None, "raw": "", "events": []}
        started = threading.Event()

        def consume() -> None:
            headers = {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.user_token}",
            }
            req = urllib.request.Request(
                self.base_url + f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
                method="POST",
                headers=headers,
                data=json.dumps({"question": question, "requestId": request_id}, ensure_ascii=False).encode(),
            )
            deadline = time.time() + hold_seconds
            with urllib.request.urlopen(req, timeout=60) as response:
                for line in response:
                    text = line.decode()
                    observed["raw"] += text
                    if text.startswith("event:"):
                        observed["events"].append(text[len("event:"):].strip())
                    if text.startswith("data:") and observed["generationId"] is None \
                            and "generationId" in text:
                        observed["generationId"] = json.loads(text[len("data:"):].strip())["generationId"]
                        started.set()
                    if started.is_set() and time.time() > deadline:
                        break

        worker = threading.Thread(target=consume, daemon=True)
        worker.start()
        self.assertTrue(started.wait(timeout=30), f"未收到 started 事件: {observed['raw']}")
        observed["worker"] = worker
        return observed

    def _retrievable_conversation(self) -> int:
        knowledge_base_id = int(RETRIEVABLE_KB_ID)
        return self.create_conversation(self.user_token, knowledge_base_id)

    @unittest.skipUnless(HAS_RETRIEVABLE_DOCS and RETRIEVABLE_KB_ID,
                         "需真实可检索文档才能稳定制造 STARTED 生成窗口；"
                         "无文档环境下拒答秒级终态无法触发停止，"
                         "该事项由 Java 单测、live MySQL 集成测试与 Python 契约测试确定性验证")
    def test_stop_marks_stopped_and_keeps_the_partial_answer(self) -> None:
        conversation_id = self._retrievable_conversation()
        observed = self._stream_until_started(
            conversation_id, f"stop-{int(time.time())}", "请详细展开说明", hold_seconds=15)
        generation_id = observed["generationId"]

        status, raw = self.request(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}/stop",
            self.user_token,
        )
        self.assertEqual(200, status, raw)
        snapshot = json.loads(raw)["data"]
        self.assertEqual("STOPPED", snapshot["status"], "显式停止必须裁决为 STOPPED，而不是 UNFINISHED")
        self.assertTrue(snapshot["ready"], "停止是终态，状态端点应可读取")

        observed["worker"].join(timeout=20)
        self.assertIn("stopped", observed["events"], f"停止后流必须以 stopped 结束: {observed['raw']}")

        # 终态与残缺正文都由 Java 落库，重新读取仍然一致。
        persisted = self.answer_state(self.user_token, conversation_id, generation_id)
        self.assertEqual("STOPPED", persisted["status"])
        self.assertEqual(snapshot["answer"], persisted["answer"], "停止后残缺正文必须稳定保留")

        # 停止释放了会话闸门，可立即再次提问。
        retry = self.stream_events(self.user_token, conversation_id, "停止后应可继续提问",
                                   f"stop-retry-{int(time.time())}")
        self.assertIn("event:started", retry, retry)

    @unittest.skipUnless(HAS_RETRIEVABLE_DOCS and RETRIEVABLE_KB_ID,
                         "需真实可检索文档才能稳定制造 STARTED 生成窗口")
    def test_second_stop_is_rejected_without_rewriting_the_terminal_state(self) -> None:
        conversation_id = self._retrievable_conversation()
        observed = self._stream_until_started(
            conversation_id, f"stop-twice-{int(time.time())}", "请详细展开说明", hold_seconds=15)
        generation_id = observed["generationId"]

        first = self.json_code(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}/stop",
            self.user_token,
        )
        self.assertEqual(200, first)
        observed["worker"].join(timeout=20)
        stopped = self.answer_state(self.user_token, conversation_id, generation_id)

        second = self.json_code(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}/stop",
            self.user_token,
        )
        self.assertEqual(ANSWER_NOT_STOPPABLE, second, "已终态回答的重复停止必须被拒绝")

        after = self.answer_state(self.user_token, conversation_id, generation_id)
        self.assertEqual(stopped["status"], after["status"], "被拒绝的停止不得改写终态")
        self.assertEqual(stopped["answer"], after["answer"], "被拒绝的停止不得改写正文")

    @unittest.skipUnless(HAS_RETRIEVABLE_DOCS and RETRIEVABLE_KB_ID,
                         "需真实可检索文档才能稳定制造 STARTED 生成窗口")
    def test_disconnect_is_not_a_business_cancel(self) -> None:
        conversation_id = self._retrievable_conversation()
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.user_token}",
        }
        req = urllib.request.Request(
            self.base_url + f"/ruoyi-kb-management/conversations/{conversation_id}/questions",
            method="POST",
            headers=headers,
            data=json.dumps(
                {"question": "请详细展开说明", "requestId": f"drop-{int(time.time())}"},
                ensure_ascii=False,
            ).encode(),
        )
        generation_id = None
        response = urllib.request.urlopen(req, timeout=60)
        for line in response:
            text = line.decode()
            if text.startswith("data:") and "generationId" in text:
                generation_id = json.loads(text[len("data:"):].strip())["generationId"]
                break
        response.close()  # 客户端直接断开，不发送任何停止命令
        self.assertIsNotNone(generation_id)

        deadline = time.time() + 30
        snapshot = self.answer_state(self.user_token, conversation_id, generation_id)
        while not snapshot["ready"] and time.time() < deadline:
            time.sleep(1)
            snapshot = self.answer_state(self.user_token, conversation_id, generation_id)

        self.assertTrue(snapshot["ready"], "连接断开后回答必须收敛到终态")
        self.assertEqual("UNFINISHED", snapshot["status"],
                         "仅断开连接不是业务取消，终态必须是 UNFINISHED 而不是 STOPPED")

    def test_stopping_another_users_answer_is_refused(self) -> None:
        knowledge_base_id = self.create_knowledge_base(f"cross-stop-{int(time.time())}")
        conversation_id = self.create_conversation(self.user_token, knowledge_base_id)
        raw = self.stream_events(self.user_token, conversation_id, "owner question",
                                 f"cross-stop-{int(time.time())}")
        generation_id = self._generation_id_from_stream(raw)
        self.assertIsNotNone(generation_id)
        before = self.answer_state(self.user_token, conversation_id, generation_id)

        code = self.json_code(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}/stop",
            self.other_user_token,
        )
        self.assertEqual(CONVERSATION_FORBIDDEN, code, "他人不得停止本用户的回答")

        after = self.answer_state(self.user_token, conversation_id, generation_id)
        self.assertEqual(before["status"], after["status"], "越权停止不得改写终态")

    def test_stopping_an_already_finished_answer_is_refused(self) -> None:
        knowledge_base_id = self.create_knowledge_base(f"finished-{int(time.time())}")
        conversation_id = self.create_conversation(self.user_token, knowledge_base_id)
        # 空知识库会秒级拒答，回答直接进入 REFUSED 终态。
        raw = self.stream_events(self.user_token, conversation_id, "no docs should refuse",
                                 f"finished-{int(time.time())}")
        self.assertIn("event:refused", raw, raw)
        generation_id = self._generation_id_from_stream(raw)
        self.assertIsNotNone(generation_id)

        code = self.json_code(
            "POST",
            f"/ruoyi-kb-management/conversations/{conversation_id}/answers/{generation_id}/stop",
            self.user_token,
        )
        self.assertEqual(ANSWER_NOT_STOPPABLE, code, "已终态回答不可停止")

        after = self.answer_state(self.user_token, conversation_id, generation_id)
        self.assertEqual("REFUSED", after["status"], "被拒绝的停止不得把 REFUSED 改写为 STOPPED")


if __name__ == "__main__":
    unittest.main()
