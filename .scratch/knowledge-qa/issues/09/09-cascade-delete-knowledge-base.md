# 09 — 实现知识库级联删除

**What to build:** 知识管理员能够一次删除整个知识库，系统立即关闭其所有新操作并在后台级联清理企业文档；失败时保持关闭且可以安全重试，而普通用户仍能回看标记后的历史。

**Blocked by:** 06 — 完成文档删除与名称释放; 07 — 完善会话、历史与流式中断.

**Status:** ready-for-agent

- [ ] 删除知识库要求二次确认，提交后立即进入删除中，并拒绝新的上传、会话和问答。
- [ ] 后台级联清理知识库内所有企业文档、MinIO 对象、解析产物和 Milvus 向量，全部成功后删除知识库活动记录。
- [ ] 任一清理失败时知识库进入删除失败、展示原因且仅允许查看和重试删除，不恢复为可用。
- [ ] 删除与重试是幂等的，并阻止并发上传或提问在清理窗口重新产生数据。
- [ ] 已有会话与问答记录继续可读并标记“知识库已删除”，但不能继续提问；删除知识库不删除历史反馈。

## Comments

### 2026-07-31 Split into tracer-bullet tickets

This ticket has been split into the following sub-tickets to keep each implementation window focused:

- [09a — 知识库级联删除 happy path：关闭闸门、后台清理、历史标记](09a-cascade-delete-happy-path.md)
- [09b — 级联删除失败诊断、幂等重试与并发安全](09b-cascade-delete-failure-idempotency-safety.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.
