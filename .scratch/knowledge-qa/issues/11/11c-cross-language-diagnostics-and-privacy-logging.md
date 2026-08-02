# 11c — 跨语言诊断日志关联与隐私安全日志

**What to build:** 请求/任务 ID 贯穿 Java、Python、MinIO、Milvus 与模型调用，技术日志可端到端诊断，同时自动化隐私测试证明日志不扩散问答与文档正文，也不记录百炼凭据。

**Blocked by:** 04 — 实现批量上传与同名原子校验; 08 — 建立用户反馈隐私闭环; 09 — 实现知识库级联删除; 10 — 接入百炼 qwen-plus 生成适配器.

**Status:** ready-for-agent

- [ ] 统一请求/任务 ID 贯穿 Java → Python → MinIO/Milvus → 模型调用，可端到端串联同一次操作。
- [ ] 技术日志记录实例、阶段、文档/片段 ID、检索分数、阶段耗时、模型请求 ID、SSE 进度、重试与错误栈。
- [ ] 隐私安全：日志不含问题正文、片段正文、完整提示词、完整或残缺回答，也不记录百炼凭据。
- [ ] 验证：自动化隐私日志断言（Java + Python）证明技术日志可诊断但不含上述正文与凭据，且请求/任务 ID 可跨端关联。

### 下游合同（来自 08b，2026-08-02）

08b 已建好管理端响应形状 `retrievalDiagnostics` / `stageDurations`，数据由 11c 提供：

- `AdminFeedbackDetail.retrievalDiagnostics: List<RetrievedChunkDiagnostic>`
  - 元素：`documentId: String`、`chunkId: String`、`score: Double`
  - 设计意图：只承载片段标识与检索分数，不含片段正文
- `AdminFeedbackDetail.stageDurations: Map<String, Long>`
  - 建议键：`embedding` / `retrieval` / `generation`（与 Python 阶段命名对齐后定）
  - 单位：毫秒
- 08b 落地时这些字段恒为空集合；UI 显示「检索片段与阶段耗时的采集尚未接入」

11c 完成 Python → 契约 → Java → MySQL 链路后，管理端详情自动填上数据，**无需再改管理端 API 形状**。

### 11c 契约扩展约束（来自 AGENTS.md）

跨 Java/Python wire contract 改动必须同时验证根 schema/样例与两端 consumer/provider：
扩展 `contracts/java-python-rag/v1/events/completed.schema.json` 与 `refused.schema.json` 时，
需同步更新 examples 与 Java 端 `AnswerEvent` 反序列化、Python 端 `model/events.py`，
且本次变更内需在真实环境验证端到端诊断信息可观测。
