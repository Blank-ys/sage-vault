# 08a — 用户提交问答反馈与同意共享

**What to build:** 普通用户能够主动对自己的完整、已停止或未完成问答提交反馈，选择答案错误、未找到答案、回答不完整或其他并填写可选说明；提交界面明确说明该问题和回答将共享给知识管理员。

**Blocked by:** 07 — 完善会话、历史与流式中断.

**Status:** ready-for-agent

- [ ] 新增反馈表（含 id、qa_id、user_id、category、comment、status、admin_note、created_at、updated_at）。
- [ ] 反馈提交 API：类别为 答案错误/未找到答案/回答不完整/其他，附可选说明。
- [ ] 仅允许用户对自己的 `completed`/`stopped`/`incomplete` 问答提交反馈。
- [ ] 提交界面明确告知该问题和回答将共享给知识管理员（同意边界）。
- [ ] 前端提供反馈入口与提交表单。
- [ ] 验证：受影响 Java 模块测试 + 前端 `build:prod` + 系统验收覆盖用户只能对自己的问答提交反馈。
