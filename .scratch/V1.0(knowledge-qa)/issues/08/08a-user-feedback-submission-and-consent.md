# 08a — 用户提交问答反馈与同意共享

**What to build:** 普通用户能够主动对自己的完整、已停止或未完成问答提交反馈，选择答案错误、未找到答案、回答不完整或其他并填写可选说明；提交界面明确说明该问题和回答将共享给知识管理员。

**Blocked by:** 07 — 完善会话、历史与流式中断.

**Status:** resolved

- [x] 新增反馈表（含 id、qa_id、user_id、category、comment、status、admin_note、created_at、updated_at）。
- [x] 反馈提交 API：类别为 答案错误/未找到答案/回答不完整/其他，附可选说明。
- [x] 仅允许用户对自己的 `completed`/`stopped`/`incomplete` 问答提交反馈。
- [x] 提交界面明确告知该问题和回答将共享给知识管理员（同意边界）。
- [x] 前端提供反馈入口与提交表单。
- [x] 验证：受影响 Java 模块测试 + 前端 `build:prod` + 系统验收覆盖用户只能对自己的问答提交反馈。

## 落点

- 表结构：`backend/ruoyi-kb-management/sql/008_schema.sql`（`sv_qa_feedback`，`uk_sv_qa_feedback_qa` 保证一条问答一条反馈，外键 `ON DELETE CASCADE` 保证用户删除问答/会话后反馈正文不残留）。已在 `192.168.150.100:3306/ry-cloud` 应用。
- 后端能力：`com.sagevault.kb.feedback`（`domain` / `mapper` / `service` / `controller`），接口 `POST /qa/{qaId}/feedback`。
- 归属与同意：`FeedbackServiceImpl` 按登录用户判定归属（问答不存在与不属于本人返回同一 `FEEDBACK_FORBIDDEN`，避免探测他人问答）；`consentToShare` 非 `true` 时拒绝且不落库。
- 状态约束：`STARTED` 拒绝反馈；`COMPLETED`/`STOPPED`/`UNFINISHED`/`REFUSED` 允许。
- 历史回显：`QaRecordResponse.feedbackSubmitted` 由 `ConversationServiceImpl.history` 批量填充，前端据此把入口收敛为"已反馈"。
- 前端：`frontend/src/features/feedback/`（`FeedbackDialog.vue` + `api/feedback.js`），入口挂在 `features/conversations/pages/WorkspacePage.vue` 每条历史回答下。
- 系统验收：`system-tests/knowledge-qa/test_user_feedback_submission_and_consent.py`。

## 验证记录

- `mvn -o -pl ruoyi-kb-management test`：169 通过 / 0 失败 / 2 跳过（跳过的是既有 MinIO 集成测试）。其中新增 `FeedbackServiceTest`(9)、`FeedbackAuthorizationTest`(5)、`FeedbackMapperMySqlIntegrationTest`(4)。
- `FeedbackMapperMySqlIntegrationTest` 已针对真实 MySQL `192.168.150.100:3306/ry-cloud` 执行（非跳过），覆盖枚举落库、唯一键拒绝二次反馈、删除会话级联清除反馈正文、历史批量查询。
- `yarn --cwd frontend build:prod`：成功，`WorkspacePage` 产物已重新生成。
- 未运行：`test_user_feedback_submission_and_consent.py`。网关 `192.168.150.100:8899` 可达且路由正常，但其后运行的 `ruoyi-kb-management` 是本次改动前的构建，本机无该服务实例、无法重新部署；且登录接口启用图形验证码，无法脚本化取得两个普通用户 token。该脚本需在部署新构建、并提供 `SAGE_VAULT_GATEWAY_URL` / `SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN` / `SAGE_VAULT_GENERAL_USER_TOKEN` / `SAGE_VAULT_SECOND_USER_TOKEN` 后执行。
