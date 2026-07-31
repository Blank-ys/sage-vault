# 06a — 文档删除 happy path：立即退出检索、异步清理、记录移除与名称释放

**What to build:** 知识管理员在文档列表删除一篇企业文档后，文档立即退出问答检索，后台异步完成 Milvus 向量与 MinIO 原文件清理，清理成功后文档从管理列表消失且同知识库内可重新上传同名文件。

**Blocked by:** 05 — 完成文档失败重试与原子发布（已 resolved）.

**Status:** ready-for-agent

- [ ] 删除 API 通过 CAS 将 AVAILABLE → DELETING；非 AVAILABLE 状态拒绝删除并返回明确业务错误。
- [ ] DELETING 状态文档不参与问答检索，Q&A 对仅含该文档的知识库返回拒答。
- [ ] 契约新增 cleanup command（Java → Python）与 cleanup callback（Python → Java）endpoint；Python 接收命令后幂等清理 Milvus 向量并回调结果。
- [ ] Java 收到成功回调后删除 MinIO 原文件（含解析产物前缀）并移除 DB 文档记录。
- [ ] 记录移除后同知识库内 `findByKbIdAndNormalizedName` 不再命中，同名文件可重新上传。
- [ ] 前端文档列表增加删除按钮与确认弹窗；DELETING 状态展示"删除中"标签；清理完成后列表刷新不再显示该文档。
- [ ] 系统验收：上传 → AVAILABLE → 删除 → 立即不可检索 → 清理完成 → 名称释放可重新上传。
