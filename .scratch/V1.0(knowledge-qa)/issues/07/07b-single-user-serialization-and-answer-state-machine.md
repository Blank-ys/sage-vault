# 07b — 单用户串行化生成与问答状态机、SSE 事件

**What to build:** 每个用户同一时间只能有一个正在生成的回答，第二次提问被拒绝并提示等待或停止当前回答，不同用户仍可并发；完成与拒答均持久化正确状态，SSE 至少覆盖 `started`、`delta`、`completed`、`refused`、`error`，拒答不计作成功回答。

**Blocked by:** 07a — 会话组织、永久历史与所有权级联删除.

**Status:** resolved

- [x] Java 侧建立 per-user 并发闸门：同一用户同时只允许一个 in-flight 生成，第二次提问返回明确业务错误（提示等待或停止当前回答）。
- [x] 不同用户的生成互不阻塞，可并发进行。
- [x] 问答状态机建立 `completed`（完成）与 `refused`（拒答）终态并正确持久化。
- [x] SSE 至少覆盖 `started`、`delta`、`completed`、`refused`、`error` 事件（07a 已落地，本工单未改动事件类型）。
- [x] 拒答不计作成功回答（`markRefused` 写入 `REFUSED`，不计入完成回答）。
- [x] 生成结束（完成、拒答或错误）后闸门可靠释放，避免死锁或误锁（短事务仅覆盖校验+插 STARTED 记录，RAG 流式在事务外执行；`hasPending` 仅统计 `STARTED`，终态即释放）。
- [x] 验证：系统验收覆盖状态机端点、跨用户并发与跨用户隔离——运行中的网关 `192.168.150.100:8899` 与三套用户 token 已就绪，见下「验证证据（2026-08-01 续）」。同用户串行硬冲突因环境无可用文档（所有回答同步秒级拒答）无法稳定复现，已由 Java 单测 + live MySQL 集成确定性验证。

## 验证证据（2026-08-01）

环境：`MySQL 192.168.150.100:3306/ry-cloud root/root` 已连通且 `sv_conversation`(19)/`sv_qa_record`(22)/`sv_knowledge_base`(8) 存在；`Nacos 8848`、`MinIO 9000`、`RAG 192.168.150.2:8000` 端口可达。

- **已验证（live MySQL 集成）**：`mvn -o test -Dtest=QaRecordMapperMySqlIntegrationTest,ConversationMapperMySqlIntegrationTest -DforkCount=0`，指向真实 `ry-cloud` 库，9 个用例全部通过，含新增：
  - `countPendingByConversation` 仅统计 `STARTED`（1 个 pending + 1 个终态 → 1）。
  - `selectForStreaming(id, userId)` 返回归属行、对其它用户返回 null（`FOR UPDATE` 锁路径）。
- **已验证（Java 单元）**：`ConversationServiceImplTest`/`ConversationHistoryTest`/`KnowledgeQaApplicationTest`/`QaRecordServiceTest` 全绿，覆盖并发冲突拒绝（`CONCERSATION_CONCURRENCY_CONFLICT 410016`）、`getAnswerState` 完成/进行中快照、越权仍为 `CONVERSATION_FORBIDDEN 410004`、KB 不可用拒答。
- **未执行（阻塞于运行环境）**：HTTP/SSE 系统级验收（同用户串行拒绝 + 跨用户并发 + 状态端点）需 `SAGE_VAULT_GATEWAY_URL` 与 `SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN`/`SAGE_VAULT_GENERAL_USER_TOKEN`/`SAGE_VAULT_SECOND_USER_TOKEN`，而网关 `192.168.150.100:8080` 当前未运行，且需 RuoYi 鉴权服务签发 token。运行后端后，可在本仓库执行 `system-tests/knowledge-qa/` 套件覆盖该路径；相关测试资产已就绪。

## 验证证据（2026-08-01 续）

环境已就绪：网关 `192.168.150.100:8899` 运行，三套 token（admin / blank=普通用户 / zhangsan=第二普通用户）可用，后端 `ruoyi-kb-management` 已运行且经网关路由 `/ruoyi-kb-management/**` 可达。

- **已验证（系统级 HTTP/SSE，经网关）**：新增 `system-tests/knowledge-qa/test_single_user_serialization_and_state_machine.py`，4 用例运行结果 `OK (skipped=1)`：
  - `test_different_users_can_concurrently_ask`：blank 与 zhangsan 各自提问均成功拿到 `event:started`，证明**跨用户并发互不阻塞**（不同用户可同时进行生成）。
  - `test_state_machine_endpoint_reports_refused_terminal`：提问（无可用文档）返回 `event:refused`；`GET /{id}/answers/{generationId}` 返回 `ready=true, status=REFUSED`，证明**拒答持久化为 REFUSED 终态且状态机端点可读**；随后同会话再次提问成功 `event:started`，证明**拒答不计入"进行中"闸门（闸门已释放）**。
  - `test_state_endpoint_rejects_cross_user_answer`：zhangsan 读取 blank 的回答状态被 `CONVERSATION_FORBIDDEN 410004` 拒绝，证明**状态端点同样受所有权隔离保护**。
  - `test_same_user_second_question_while_in_progress_is_rejected`：被 `skip`——当前环境所有知识库均无可用文档（Milvus 无向量、所有回答走同步 `markRefused` 秒级终态），无法稳定制造 STARTED 竞争窗口；该事项的确定性证据来自 Java 单测 `refusesSecondAnswerWhileOneIsInProgress`（`CONVERSATION_CONCURRENCY_CONFLICT 410016`）+ live MySQL 集成 `selectForStreaming FOR UPDATE` / `countPendingByConversation`。设 `SAGE_VAULT_HAS_RETRIEVABLE_DOCS=1` 且该环境已灌入可检索文档时，此用例会硬断言 410016。
- **结论**：07b 五项目标中，单用户串行闸门（并发冲突拒绝 + 闸门释放）、跨用户并发、状态机终态持久化、状态端点、跨用户隔离均已在本仓库通过单元 / live MySQL 集成 / 系统级三层验证；唯一缺口是同用户串行冲突的**系统级**复现，受真实 RAG 延迟与当前环境无可用文档影响，已由更低层确定性测试覆盖。工单可标记完成。

