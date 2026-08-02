# 09 — 实现知识库级联删除

**What to build:** 知识管理员能够一次删除整个知识库，系统立即关闭其所有新操作并在后台级联清理企业文档；失败时保持关闭且可以安全重试，而普通用户仍能回看标记后的历史。

**Blocked by:** 06 — 完成文档删除与名称释放; 07 — 完善会话、历史与流式中断.

**Status:** resolved

- [x] 删除知识库要求二次确认，提交后立即进入删除中，并拒绝新的上传、会话和问答。
- [x] 后台级联清理知识库内所有企业文档、MinIO 对象、解析产物和 Milvus 向量，全部成功后删除知识库活动记录。
- [x] 任一清理失败时知识库进入删除失败、展示原因且仅允许查看和重试删除，不恢复为可用。
- [x] 删除与重试是幂等的，并阻止并发上传或提问在清理窗口重新产生数据。
- [x] 已有会话与问答记录继续可读并标记“知识库已删除”，但不能继续提问；删除知识库不删除历史反馈。

## Comments

### 2026-07-31 Split into tracer-bullet tickets

This ticket has been split into the following sub-tickets to keep each implementation window focused:

- [09a — 知识库级联删除 happy path：关闭闸门、后台清理、历史标记](09a-cascade-delete-happy-path.md)
- [09b — 级联删除失败诊断、幂等重试与并发安全](09b-cascade-delete-failure-idempotency-safety.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.

### 2026-08-02 09a 已完成

09a（happy path）已实现并验证，对应上面第 1、2、5 条勾选。剩余两条是 09b 的范围：失败态的完整诊断/只读约束与并发安全证明。

09a 已经落下的相关基础（09b 可直接复用，但仍需 09b 自己的验收）：`DELETE_FAILED` 状态与 `error_message` 落库、失败时保留残留不回到 `AVAILABLE`、`DELETE_FAILED` 允许重发删除、删除接口幂等、`cleanup_attempt` 残留计数。09a 未证明并发窗口下的上传/提问安全，也未覆盖失败态的前端只读约束。

### 2026-08-02 09b 已完成，09 整体 resolved

09b 实现并验证完毕：失败阶段诊断、DELETE_FAILED 只读约束、幂等重试、并发窗口安全、旁邻知识库安全、前端失败标签与重试入口均已落地。子工单 09a 与 09b 均为 resolved，父工单 09 全部验收点勾选完成。
