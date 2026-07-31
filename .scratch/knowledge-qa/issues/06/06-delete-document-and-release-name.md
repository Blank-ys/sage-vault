# 06 — 完成文档删除与名称释放

**What to build:** 知识管理员删除企业文档后，它会立即停止影响新问答，并在后台完整清理原文件和向量；只有清理成功后，文档才从列表移除并允许重新使用原文件名。

**Blocked by:** 05 — 完成文档失败重试与原子发布.

**Status:** ready-for-agent

- [ ] 删除请求使文档立即进入删除中并退出新检索，随后异步清理 MinIO 原文件、解析产物和 Milvus 向量。
- [ ] 清理成功后活动文档记录从管理列表移除，知识库内对应文件名释放并可重新上传。
- [ ] 清理完成前同名上传仍被拒绝；删除调用和清理重试具备幂等性，不误删后来上传的文档。
- [ ] 清理失败保留可诊断状态与重试入口，文档始终保持不可检索。
- [ ] 删除企业文档不删除已有会话、问答记录或已提交反馈，系统验收验证立即退出检索和最终存储清理。

## Comments

### 2026-07-31 Split into tracer-bullet tickets

This ticket has been split into the following sub-tickets to keep each implementation window focused:

- [06a — 文档删除 happy path：立即退出检索、异步清理、记录移除与名称释放](06a-delete-happy-path-retrieval-exit-cleanup-name-release.md)
- [06b — 删除清理失败诊断、幂等重试与安全不变量](06b-delete-cleanup-failure-idempotency-safety.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.
