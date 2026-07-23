# 08 — 以 fail-closed 方式变更可见范围

**What to build:** 制度管理员能够异步修改已生效制度文档的可见范围；请求返回前旧标签即被哨兵标签取代，更新窗口和失败状态下文档对所有人保持不可检索，成功后才按新范围重新开放。

**Blocked by:** 05 — 让受限制度只对获授权员工可见

**Status:** ready-for-agent

- [ ] 仅 `ingestStatus=READY` 且 `visibilityStatus=SYNCED` 的制度文档允许发起变更，其他状态下接口和页面均拒绝重复或竞态请求。
- [ ] 合法请求在同步返回前把该制度文档的全部片段改为哨兵标签，并将 `visibilityStatus` 从 `SYNCED` 更新为 `UPDATING`，随后异步触发重打标签。
- [ ] 成功回调使状态变为 `SYNCED` 并应用新标签；触发或处理失败使状态变为 `FAILED`，保留原因和哨兵标签，不回退旧标签。
- [ ] 管理员可查看可见范围处理状态并重试失败变更；连接真实 Milvus 的测试证明原获授权员工在 `UPDATING` 和 `FAILED` 状态下也无法召回该制度。
