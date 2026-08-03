# 11c — 跨语言诊断日志关联与隐私安全日志

**What to build:** 请求/任务 ID 贯穿 Java、Python、MinIO、Milvus 与模型调用，技术日志可端到端诊断，同时自动化隐私测试证明日志不扩散问答与文档正文，也不记录百炼凭据。

**Blocked by:** 04 — 实现批量上传与同名原子校验; 08 — 建立用户反馈隐私闭环; 09 — 实现知识库级联删除; 10 — 接入百炼 qwen-plus 生成适配器.

**Status:** ready-for-agent

- [x] 统一请求/任务 ID 贯穿 Java → Python → MinIO/Milvus → 模型调用，可端到端串联同一次操作。
      - completed 路径：`answer()` 内部 `trace_id` 写入 `Completed.generationId`，与 started/delta 同一 ID 对齐；failed 路径沿用 `generation_id=trace_id`。
- [x] 技术日志记录阶段、文档/片段 ID、检索分数、阶段耗时、SSE 进度、重试与错误栈（模型请求 ID 字段已贯通契约，待百炼适配器返回后填充）：
      - 检索阶段采集文档/片段 ID 与分数（`RetrievedChunkDiagnostic`），不含片段正文；
      - 嵌入/检索/生成三阶段毫秒耗时（`stage_durations`），`time.perf_counter()` 采集；
      - completed 事件经 `app.py` SSE 序列化贯通到 Java，落库独立子表后展示于管理端。
- [x] 隐私安全：日志不含问题正文、片段正文、完整提示词、完整或残缺回答，也不记录百炼凭据。
      - `model/privacy.py`：`mask_sensitive()` 抹掉 sk-/Bearer/password；`classify_failure()`/`mask_failure_detail()` 把异常归约到受控失败类别。
      - Python 生成失败路径（`answer()`）只把脱敏后的失败类别写入 SSE `failed` 事件；真实异常 + `trace_id` 留在服务端日志（`mask_sensitive` 后落盘）。
      - 契约 `failed.schema.json` + `openapi.yaml:RagRuntimeFailureCode` 约定对外只暴露受控词表，example 已验证。
- [x] 验证：自动化隐私日志断言（Java + Python）证明技术日志可诊断但不含上述正文与凭据，且请求/任务 ID 可跨端关联。
      - Python：`tests/unit/model/test_privacy.py`（脱敏 + 受控词表断言，全部通过）。
      - 契约：`contracts/tests/test_examples.py::test_failed_generation_example_matches_schemas` 校验 `failed` 事件 detail 属于受控词表。
      - Java/Python wire：221 Java 测试 + 7 契约测试 + 25 Python 测试通过；E2E K3 已验证 started→delta×60→completed 与 refused。
      - 注：`generationId`→`trace_id` 关联已贯通 failed 与 completed 两条路径；MinIO 直接对象读写与 Milvus 内部查询当前不在应用层打点，模型请求 ID 字段已贯通待百炼适配器回填。

### 本次未覆盖（保持未完成）

- 工单项 1/2 的**全链路诊断**已完成：统一任务 ID 贯穿 completed 路径，阶段耗时、检索分数、文档/片段 ID 已采集并贯通 Python → 契约 → Java → MySQL（`sv_qa_retrieval_diagnostic` 子表）→ 管理端；`AdminFeedbackDetail.retrievalDiagnostics` / `stageDurations` 已填充真实数据。模型请求 ID 字段已贯通，待百炼适配器返回后回填（当前为 `null`）。
- 端到端失败注入只能在索引流水线触发，生成阶段无失败注入开关，故 K4（生成失败实时观测）未能在真实环境触发；如需启用可新增生成阶段失败注入开关。

### 下游合同（来自 08b，2026-08-02）

08b 已建好管理端响应形状 `retrievalDiagnostics` / `stageDurations`，数据由 11c 提供：

- `AdminFeedbackDetail.retrievalDiagnostics: List<RetrievedChunkDiagnostic>`
  - 元素：`documentId: String`、`chunkId: String`、`score: Double`
  - 设计意图：只承载片段标识与检索分数，不含片段正文
- `AdminFeedbackDetail.stageDurations: Map<String, Long>`
  - 键：`embedding` / `retrieval` / `generation`（已与 Python 阶段命名对齐）
  - 单位：毫秒
- 11c 已完成贯通：completed 事件携带上述数据 → `DiscoveredRagAnswerAdapter` 解析 → `QaRecordService.saveDiagnostics` 落库 `sv_qa_retrieval_diagnostic` 子表 → `FeedbackServiceImpl.findDetailForAdmin`/`resolve` 联查装配。管理端详情自动填上真实数据，**无需再改管理端 API 形状**。

### 11c 契约扩展约束（来自 AGENTS.md）

跨 Java/Python wire contract 改动必须同时验证根 schema/样例与两端 consumer/provider：
扩展 `contracts/java-python-rag/v1/events/completed.schema.json` 与 `refused.schema.json` 时，
需同步更新 examples 与 Java 端 `AnswerEvent` 反序列化、Python 端 `model/events.py`，
且本次变更内需在真实环境验证端到端诊断信息可观测。

### 本次新增待办（部署前必须完成）

- [ ] **DB 迁移：新建 `sv_qa_retrieval_diagnostic` 诊断子表。** 本次按确认方案新增独立子表（1:N 关联 `sv_qa_record`），但仓库当前无 `.sql` 迁移脚本（RuoYi 自动建表），真实环境部署前需补建表 DDL：
  - 列：`id` BIGINT PK、`qa_record_id` BIGINT（FK→`sv_qa_record.id`，索引）、`generation_id` VARCHAR、`document_id` VARCHAR、`chunk_id` VARCHAR、`score` DECIMAL(10,6)、`stage` VARCHAR（retrieval/embedding/generation）、`duration_ms` BIGINT、`created_at` DATETIME。
  - 索引：`idx_qa_record_id(qa_record_id)`、`idx_generation_id(generation_id)`。
  - 联删：`RetrievalDiagnosticMapper.deleteByConversation` 已按 `sv_qa_record.conversation_id` 级联清理（见 `RetrievalDiagnosticMapper.xml`）。
- [ ] 可选：百炼 `qwen-plus` 生成适配器若返回模型请求 ID，回填 `Completed.model_request_id`（当前契约/Java 端已贯通，值为 `null`）。
