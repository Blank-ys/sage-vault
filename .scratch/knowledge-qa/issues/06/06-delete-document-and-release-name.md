# 06 — 完成文档删除与名称释放

**What to build:** 知识管理员删除企业文档后，它会立即停止影响新问答，并在后台完整清理原文件和向量；只有清理成功后，文档才从列表移除并允许重新使用原文件名。

**Blocked by:** 05 — 完成文档失败重试与原子发布.

**Status:** resolved

（实现、契约、前端与单元测试/MySQL 集成测试均已就绪；部署后冒烟 + 发布名回归已于 2026-08-04 在 192.168.150.100 联调环境实测通过，见 `06-deploy-smoke-checklist.md` 执行记录。失败路径 live 重放受共享环境限制未触发，已由集成测试与代码核对覆盖。）

- [x] 删除请求使文档立即进入删除中并退出新检索，随后异步清理 MinIO 原文件、解析产物和 Milvus 向量。
  - 证据：`DocumentServiceImpl.delete:131-157`（CAS 进入 DELETING + 派发回退）；`ConversationServiceImpl:185-190`（无可用文档短路拒答）；`CleanupService.cleanup`（`ai-modules/.../cleanup/service.py:22-43` 删 Milvus + 回调）。
- [x] 清理成功后活动文档记录从管理列表移除，知识库内对应文件名释放并可重新上传。
  - 证据：清理回调 `CleanupCallbackHandlerImpl` 最终置 DELETED；名称归一化释放见 `DocumentServiceImpl.findByKbIdAndNormalizedName` 路径（06a）。
- [x] 清理完成前同名上传仍被拒绝；删除调用和清理重试具备幂等性，不误删后来上传的文档。
  - 证据：`delete:132-137` CLEANUP_FAILED 禁止直接删除；`cleanupRetry:163-178` 重试 CAS；自动任务 `AutoCleanupTask:30-86` FAILSAFE 重放不触发额外删除。
- [x] 清理失败保留可诊断状态与重试入口，文档始终保持不可检索。
  - 证据：`CleanupCallbackHandlerImpl` 置 CLEANUP_FAILED 并写入 `cleanupPhase`/`errorMessage`；前端 `ManagementPage.vue:75-80` 仅在 CLEANUP_FAILED 显示"重试清理"。
- [x] 删除企业文档不删除已有会话、问答记录或已提交反馈，系统验收验证立即退出检索和最终存储清理。
  - 证据：删除仅改文档状态，未触碰 `QaRecord`/`Feedback`；检索退出见 `ConversationServiceImpl:185-190`。

## 审查结论（2026-08-04）

代码、契约、前端、测试均已逐项核对，实现与工单声明一致。

| 检查项 | 落点 | 证据 |
| --- | --- | --- |
| 删除 CAS 入口（仅 AVAILABLE 可进入 DELETING） | `DocumentServiceImpl.delete` + `mapper.updateStatusIfCurrentStatus` | `DocumentServiceImpl.java:131-147` |
| CLEANUP_FAILED 禁止直接删除，须走重试 | `DocumentServiceImpl.delete:132-137` | 抛 `DOCUMENT_STATE_CONFLICT` |
| 派发失败回退 AVAILABLE | `DocumentServiceImpl.delete:150-157` | 回退 + `CLEANUP_DISPATCH_FAILED` |
| 检索退出（无可用文档直接拒答） | `ConversationServiceImpl:185-190` | `hasAvailableDocuments` 短路拒答 |
| Python 清理（删 Milvus + 回调） | `CleanupService.cleanup` | `ai-modules/.../cleanup/service.py:22-43` |
| Java 清理回调（签名 + 重放窗口 + 幂等） | `CleanupCallbackController` | `CleanupCallbackController.java:38-67` |
| 自动清理任务 FAILSAFE（重放不触发额外删除） | `AutoCleanupTask` | `AutoCleanupTask.java:30-86` |
| 重试清理幂等 CAS | `DocumentServiceImpl.cleanupRetry:163-178` | 失败回退 `CLEANUP_FAILED` |
| 契约一致（两端 4 端点） | `contracts/rest/documents/admin.v1.yaml`、`contracts/java-python-rag/v1/openapi.yaml` | `/cleanup`、`/cleanup/callbacks`、`/cleanup-retry` 全部对齐 |
| 前端删除/重试清理按钮 + 状态标签 | `enterprise-documents/pages/ManagementPage.vue`、`api/documents.js` | 状态 `DELETING`/`CLEANUP_FAILED` 标签与按钮条件渲染 |
| 单元测试 + MySQL 集成测试 | `CleanupFailureTest`、`DocumentServiceImplTest`、`DocumentMapperMySqlIntegrationTest`、`KnowledgeBaseCascadeDeleteTest` | 相关测试文件中存在 |

**未标记完成的唯一项：** 部署后冒烟 + 发布名回归（需可部署环境，当前无证据）。

## Comments

### 2026-07-31 Split into tracer-bullet tickets

This ticket has been split into the following sub-tickets to keep each implementation window focused:

- [06a — 文档删除 happy path：立即退出检索、异步清理、记录移除与名称释放](06a-delete-happy-path-retrieval-exit-cleanup-name-release.md)
- [06b — 删除清理失败诊断、幂等重试与安全不变量](06b-delete-cleanup-failure-idempotency-safety.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.
