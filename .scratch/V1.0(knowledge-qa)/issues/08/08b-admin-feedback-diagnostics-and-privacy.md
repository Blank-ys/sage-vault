# 08b — 管理员反馈诊断视图、处理流程与隐私隔离

**What to build:** 知识管理员只能查看已提交反馈对应的问题、完整或残缺回答、请求 ID、检索片段标识/分数与阶段耗时，无法读取未反馈问答正文；可将反馈标记为待处理或已处理并填写内部备注；用户删除问答或会话时对应反馈正文一并删除，仅保留不含内容的审计事实。

**Blocked by:** 08a — 用户提交问答反馈与同意共享.

**Status:** resolved

- [x] 管理员反馈列表/详情 API：仅暴露已提交反馈的问答正文与诊断信息（请求 ID ✅；检索片段标识/分数 ⏸ 由 11c 提供；阶段耗时 ⏸ 由 11c 提供）。
- [x] 未提交反馈的问答正文对管理员始终不可见（隐私隔离）。
- [x] 管理员可将反馈标记为 待处理/已处理 并填写内部备注。
- [x] V1 明确不提供：管理员回复、通知、自动重跑、外部工单流转。
- [x] 用户删除问答或会话时对应反馈正文一并删除，仅保留不含内容的审计事实。
- [x] 前端提供管理员反馈处理界面。
- [x] 验证：系统验收覆盖普通用户隔离与管理员同意边界（管理员读不到未反馈问答正文）。

### 诊断字段 owner 移交说明

按 `11c-cross-language-diagnostics-and-privacy-logging.md` 的依赖方向 `08 → 11c`，本工单不抢上游 owner：

- 详情响应已固定包含 `retrievalDiagnostics` / `stageDurations` 两个字段；
- 当前 V1 实现恒返回空集合，UI 显示「检索片段与阶段耗时的采集尚未接入」；
- 11c 落地 Python 采集 → 契约扩展 → Java 落库链路时，不需要再改管理端 API 形状；
- 11c 落地完成后，本工单响应即自动填上数据。

### 实施内容

- `backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/feedback/controller/AdminFeedbackController.java`：列表 / 详情 / 状态流转；统一 `sage:feedback:manage` 权限。
- `backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/feedback/service/impl/FeedbackServiceImpl.java`：管理端三个动作；审计是远程 HTTP，留在事务外。
- `backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/platform/audit/RuoyiFeedbackAudit.java`：审计只写 `feedbackId / qaId / status`，不写正文。
- `backend/ruoyi-kb-management/src/main/resources/mapper/feedback/FeedbackMapper.xml`：列表只查反馈表，详情用 INNER JOIN 从反馈表出发，隐私边界在 SQL 层。
- `backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/platform/error/ErrorCode.java`：`FEEDBACK_NOT_FOUND = 410027`、`FEEDBACK_STATUS_INVALID = 410028`。
- `backend/ruoyi-kb-management/sql/009_seed.sql`：菜单 `问答反馈` + 仅 `knowledge_admin` 角色授权。
- `frontend/src/features/feedback/api/adminFeedback.js` + `pages/ManagementPage.vue`：管理端界面（队列、详情、处理/重开、残缺回答告警、诊断占位）。
- `system-tests/knowledge-qa/test_admin_feedback_diagnostics_and_privacy.py`：浏览器 → 网关的端到端验收。

### 验证记录

- `mvn -pl ruoyi-kb-management test -Dtest=AdminFeedback*Test,Feedback*Test` → 34 通过 / 0 失败。
- `mvn -pl ruoyi-kb-management test -Dtest=FeedbackMapperMySqlIntegrationTest`（真实 MySQL `192.168.150.100:3306/ry-cloud`）→ 10 通过 / 0 失败。
- `yarn --cwd frontend build:prod` → 构建成功。
- `GET http://192.168.150.100:8899/ruoyi-kb-management/knowledge-bases` → 200，网关路由可达。

### 系统验收（真实网关）

`python -m unittest system-tests/knowledge-qa/test_admin_feedback_diagnostics_and_privacy.py -v`（网关 `192.168.150.100:8899` + 三个真实 JWT）→ **1 passed / 0 failed**。覆盖：普通用户 403 拒绝进入队列/详情/处理；新反馈进 PENDING；详情返回已授权问答正文与请求 ID；未反馈问答无管理端入口且不在队列；处理后内部备注落库并移出 PENDING；备注不回流用户历史；可重开；删除会话后管理端返回 `FEEDBACK_NOT_FOUND`(410027)。

`009_seed.sql` 的菜单与角色授权已确认落库（菜单 ID 2002，`knowledge_admin` 授权 1 行），`sage:feedback:manage` 权限边界生效。
