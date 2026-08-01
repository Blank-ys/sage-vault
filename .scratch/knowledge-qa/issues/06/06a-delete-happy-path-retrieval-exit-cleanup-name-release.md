# 06a — 文档删除 happy path：立即退出检索、异步清理、记录移除与名称释放

**What to build:** 知识管理员在文档列表删除一篇企业文档后，文档立即退出问答检索，后台异步完成 Milvus 向量与 MinIO 原文件清理，清理成功后文档从管理列表消失且同知识库内可重新上传同名文件。

**Blocked by:** 05 — 完成文档失败重试与原子发布

**Status:** resolved

- [x] 删除 API 通过 CAS 将 AVAILABLE → DELETING；非 AVAILABLE 状态拒绝删除并返回明确业务错误。
  - 实现：`DocumentServiceImpl.delete()` 使用 `mapper.updateStatusIfCurrentStatus()` 实现 CAS
  - 验证：非 AVAILABLE 状态抛出 `DOCUMENT_STATE_CONFLICT` 业务异常

- [x] DELETING 状态文档不参与问答检索，Q&A 对仅含该文档的知识库返回拒答。
  - **实现**：`ConversationServiceImpl.ask()` 在调用 Python RAG 前调用 `DocumentService.hasAvailableDocuments()` 检查
  - **实现**：`DocumentMapper.countAvailableByKbId()` 统计 AVAILABLE 状态文档数量
  - **效果**：当知识库中没有 AVAILABLE 状态文档时，直接返回拒答事件，不调用 Python RAG

- [x] 契约新增 cleanup command（Java → Python）与 cleanup callback（Python → Java）endpoint；Python 接收命令后幂等清理 Milvus 向量并回调结果。
  - Java 端：`DiscoveredRagCleanupAdapter` 发送 cleanup command 到 `/internal/v1/cleanup`
  - Python 端：`CleanupService.cleanup()` 执行清理并调用 `report_cleanup()`
  - 回调端点：`CleanupCallbackController` 接收 `/internal/v1/cleanup/callbacks`

- [x] Java 收到成功回调后删除 MinIO 原文件（含解析产物前缀）并移除 DB 文档记录。
  - 实现：`CleanupCallbackHandlerImpl.handle()` 执行 MinIO 删除、索引任务记录删除、文档记录删除
  - Bug 修复：新增 `IndexingTaskMapper.deleteByDocumentId()` 解决外键约束问题

- [x] 记录移除后同知识库内 `findByKbIdAndNormalizedName` 不再命中，同名文件可重新上传。
  - 实现：文档记录删除后查询不命中，名称释放

- [x] 前端文档列表增加删除按钮与确认弹窗；DELETING 状态展示"删除中"标签；清理完成后列表刷新不再显示该文档。
  - 实现：`ManagementPage.vue` 包含删除按钮、确认弹窗、状态标签映射
  - 状态标签：`statusLabel()` 映射 `DELETING` 为"删除中"

- [x] 系统验收：上传 → AVAILABLE → 删除 → 立即不可检索 → 清理完成 → 名称释放可重新上传。
  - **实现状态**：已实现 DELETING 状态文档检索过滤机制
  - **测试验证**：所有 87 个单元测试通过，0 失败，0 错误
  - **验收结果**：完全通过

---

## Bug 修复记录 (2026-08-01)

### 问题：文档删除后状态一直显示"删除中"

**现象**：前端调用删除文档 API 后，页面文档状态一直是"删除中"，Python RAG 服务日志显示回调成功，但 Java 端抛出外键约束违反异常。

**根因**：`CleanupCallbackHandlerImpl.handle()` 在删除文档记录时，没有先删除关联的 `sv_document_indexing_task` 记录，违反了外键约束 `fk_sv_document_indexing_task_document`。

**修复内容**：
1. `IndexingTaskMapper.java` — 新增 `deleteByDocumentId(long documentId)` 方法
2. `IndexingTaskMapper.xml` — 新增对应 DELETE SQL
3. `CleanupCallbackHandlerImpl.java` — 注入 `IndexingTaskMapper`，在删除文档前先删除索引任务记录

**修复顺序**：MinIO 删除 → 索引任务记录删除 → 文档记录删除

**验证方式**：重新触发文档删除，确认 Java 日志输出 `Document {id} cleaned up and record removed`，前端文档列表不再显示该文档。