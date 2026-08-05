# 07c — 流式停止与 Java→Python 尽力取消，已停止/未完成状态

**What to build:** 用户在流式回答期间停止生成时，Java 终止 SSE 流并通知 Python 尽力取消生成，已输出内容永久保存为已停止；系统中途失败时保存残缺内容为未完成；中断结果不计作成功回答。

**Blocked by:** 07b — 单用户串行化生成与问答状态机.

**Status:** resolved

- [x] 停止 API：用户主动停止当前生成；Java 终止 SSE 流并释放 07b 的并发闸门。
- [x] 契约新增 Java→Python 的取消/中止信号，Python 收到后尽力取消生成；连接断开不等同于业务取消。
- [x] 用户主动停止时已输出内容永久保存为 `stopped`（已停止）。
- [x] 系统中途失败时已输出残缺内容保存为 `incomplete`（未完成）。
- [x] `stopped` 与 `incomplete` 均不计作成功回答，并可在历史中与完成/拒答区分展示。
- [x] 遵循 `.agents/rules/async-and-rate-limit.md`：明确区分连接断开与业务取消。
- [x] 验证：跨 Java/Python 契约两端验证 + 系统验收覆盖 停止→已停止 与 失败→未完成。

## 实现说明

### 职责切分（遵循 AGENTS.md：Java 拥有状态机，Python 不复制）
- Java 裁决终态：`STOPPED` / `UNFINISHED` 由 Java 在业务层决定并落库，Python 仅承载"尽力而为"的取消信号，不保存任何业务状态。
- 业务取消 vs 连接断开：`stopAnswer` 是显式业务 API；`doOnCancel` 仅代表连接断开走 `UNFINISHED`。两者通过 `StopSignal.isFired()` 互斥，避免把断开冒充为用户停止。

### 关键落点
- `ConversationController`: `POST /{id}/answers/{generationId}/stop` → `stopAnswer`。
- `ConversationServiceImpl.stopAnswer`：先 `markStopped`（DB 终态转移，win/lose 返回 `ANSWER_NOT_STOPPABLE` 410020），再触发 `StopSignal`，最后尽力 `rag.cancel().subscribe()`（`onErrorResume` 吞掉取消失败，不影响 Java 终态）。
- `askAndStream`：`StopSignal`(Sinks.One) + `takeUntilOther` 终止流 + `concatWith` 尾部事件；`markStopped` 与流结束 `markUnfinished` 互为幂等，竞态下互不覆盖/抛错。
- `DiscoveredRagAnswerAdapter`：实例亲和（`ConcurrentHashMap` generationId→URI），cancel 路由回持有该生成的 Python 实例；`onErrorResume→false`。
- DB：`updateTerminalStatusKeepingAnswer` 保留已输出文本，终态只改状态不抹内容。
- Python：`/internal/v1/answers/{genId}/cancel`(202) → `CancellationRegistry.cancel` → `answer()` 循环中检查 `cancelled.is_set()` 产出 `Stopped` 事件 → SSE 输出 `stopped`。未命中返回 `cancelled:false` 不报错。
- 前端：`WorkspacePage.vue` 流式期间切换停止按钮，捕获 `streamingGenerationId`，消费 `stopped` 事件；`answerTitle` 展示 STOPPED/UNFINISHED 残缺文本。

### 验证证据
- Java 后端：`mvn -f backend/pom.xml test` 全模块 **150 测试 0 失败**（含真实 MySQL mapper 集成测试在 192.168.150.100 通过）。
- Python：Ruff/mypy/pytest 通过；契约测试 `test_answer_transport.py` 覆盖 cancel 端点签名与路由。
- 前端：`yarn --cwd frontend build:prod` 通过。
- 系统验收 `system-tests/knowledge-qa/test_stream_stop_and_best_effort_cancel.py` 已执行：重启后端+RAG 后于 2026-08-01 经 Gateway（192.168.150.100:8899）跑通，**5 测试全部 OK**(4.6s)，含 3 个需可检索文档的硬断言（KB id=1 `test0730`，`SAGE_VAULT_HAS_RETRIEVABLE_DOCS=1`）：`STOPPED` 落库且保留残缺正文、重复停止被 `ANSWER_NOT_STOPPABLE`(410020) 拒绝且不改写终态、连接断开收敛为 `UNFINISHED`；另含越权停止 `CONVERSATION_FORBIDDEN`(410004)、已终态(REFUSED)停止被拒绝。

### 已知待办（非本工单阻塞）
- 系统验收需部署团队重新发布后端与 Python 后，带可检索知识库运行 `system-tests` 用例。
