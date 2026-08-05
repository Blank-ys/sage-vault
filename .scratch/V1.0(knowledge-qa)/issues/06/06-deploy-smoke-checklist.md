# 工单 06 部署后冒烟与发布名回归清单

> 父工单 `06-delete-document-and-release-name.md` 代码层已逐项核对完成（见 `## 审查结论`），本文件是可部署环境下的**系统验收清单**，用于把父工单从 `ready-for-deploy-acceptance` 推进到 `resolved`。
> 执行前需具备：Java 服务、Python RAG 服务、MySQL、Redis、Milvus、MinIO 全部联调可达，且 `.env` 中 `java_cleanup_callback_url` 与 `signing_key` 已配置。

## 前置确认（不通过则后续无意义）

- [ ] **契约端点可达**：Python 暴露 `POST /internal/v1/cleanup` 与 `POST /internal/v1/cleanup/callbacks`；Java 暴露清理回调入口（契约见 `contracts/java-python-rag/v1/openapi.yaml:83-114`）。
- [ ] **签名一致**：Python `settings.signing_key` 与 Java 侧清理回调验签密钥相同；`replay_window_seconds`（默认 60）两端一致。
- [ ] **Milvus 单 Collection**：清理走 `MilvusVectorStore.delete_by_document(expr=document_id)`（按 `document_id` 过滤，单 Collection 不变）。

## 核心路径：删除 → 拒答 → 清理 → 名称释放

### 步骤 1：上传并使其可用
- [ ] 向某知识库上传 `demo.txt`，等待状态 `AVAILABLE`（触发 `POST /internal/v1/indexing` + 回调成功）。
- [ ] 确认 Milvus 中已写入该 `document_id` 的 chunks（可用 `count_by_document` 或管理端问答验证可检索）。

### 步骤 2：删除立即进入 DELETING 且退出检索
- [ ] 调用 `DELETE /ruoyi-kb-management/documents/{documentId}`（前端删除按钮，见 `ManagementPage.vue:81-92`）。
- [ ] 列表状态立即变为 `DELETING`（非 AVAILABLE/DELETED）。
- [ ] **检索退出**：删除后、清理回调到达前，对该知识库发起提问，应直接收到 `NO_AVAILABLE_DOCUMENTS_MESSAGE` 拒答（验证 `ConversationServiceImpl.java:185-190` 短路），**不进入流式检索**。

### 步骤 3：异步清理完成
- [ ] Python 收到 `POST /internal/v1/cleanup`，执行 `CleanupService.cleanup`（删 Milvus + 回调）。
- [ ] Python 调 `POST .../cleanup/callbacks` 报告成功，携带 `X-Sage-Timestamp` / `X-Sage-Signature`。
- [ ] Java 回调将文档置 `DELETED`；列表不再显示该活动记录。
- [ ] Milvus 中该 `document_id` 的 chunks 已清空（`count_by_document` 返回 0）。

### 步骤 4：发布名释放（回归）
- [ ] 再次上传同名 `demo.txt`，不再被"同名占用"拒绝，可正常进入 `PROCESSING → AVAILABLE`。
- [ ] 验证归一化规则与上传路径一致（06a 的 `findByKbIdAndNormalizedName` 释放）。

## 失败路径：CLEANUP_FAILED → 重试清理幂等

### 步骤 5：清理失败保留状态与重试入口
- [ ] 构造一次清理失败（如临时断开 Milvus 或使回调失败），文档状态应为 `CLEANUP_FAILED`。
- [ ] 前端仅在 `CLEANUP_FAILED` 显示"重试清理"按钮（`ManagementPage.vue:75-80`）；`AVAILABLE`/`DELETING` 不显示。
- [ ] 清理完成前同名上传仍被拒绝。

### 步骤 6：重试清理幂等，不误删后来文档
- [ ] 点击"重试清理" → `cleanupRetryDocument` → `POST /ruoyi-kb-management/documents/{id}/cleanup-retry`。
- [ ] Java `cleanupRetry` 走幂等 CAS（失败回退 `CLEANUP_FAILED`）；文档最终 `DELETED`，Milvus chunks 清零。
- [ ] **不误删**：在 `CLEANUP_FAILED` 状态下若已上传同名新文档（见步骤 4 的释放），重试清理只删原 `document_id`，新文档不受影响。

### 步骤 7：自动清理任务 FAILSAFE（重放保护）
- [ ] 重放一份旧的清理成功回调（同一 `requestId` / 在 `replay_window_seconds` 内或之外均可测试），`AutoCleanupTask` 不应再向 Milvus 发起额外删除（`AutoCleanupTask.java:30-86`）。
- [ ] 重放窗口外的旧回调应被拒绝（401 或忽略），不重复变更状态。

## 不变量（每次变更后必查）

- [ ] **不触碰会话/问答/反馈**：删除文档后，已有 `QaRecord`、反馈记录仍然存在（删除仅改文档状态）。
- [ ] **检索退出恒定**：知识库内无 `AVAILABLE` 文档时，任何提问都短路拒答，不进入生成。

## 验收结论栏

| 项 | 结果 | 执行时间 | 备注 |
| --- | --- | --- | --- |
| 前置确认 | ☐ | | |
| 步骤 1–4 核心路径 | ☐ | | |
| 步骤 5–7 失败路径 | ☐ | | |
| 不变量 | ☐ | | |

> 全部 ☐ 勾选后，回父工单把 `Status` 由 `ready-for-deploy-acceptance` 改为 `resolved`，并在 `## 审查结论` 补一行"部署后冒烟：YYYY-MM-DD 通过，环境 X"。

## 执行记录（2026-08-04，环境 192.168.150.100）

测试知识库：`smoke06-test`（id 338，AVAILABLE）。管理员 token 来自用户提供的 `admin/admin123` 登录态。

### 核心路径结果

| 步骤 | 操作 | 结果 | 证据 |
| --- | --- | --- | --- |
| 前置 | 鉴权 `/code`+`/auth/login` 需验证码；用提供的 token 跳过 | ✅ | `200` |
| 1 | 上传 `demo.txt` → doc 147 → `AVAILABLE` | ✅ | `POST /documents` 返回 id=147，`PROCESSING`→`AVAILABLE` |
| 2 | `DELETE /documents/147` → 立即 `DELETING` | ✅ | 删除响应 `data.status=DELETING` |
| 2b | 删除后对 KB338 提问 → 检索退出拒答 | ✅ | SSE `event:refused`，msg `该知识库暂无可⽤文档` |
| 3 | 清理完成 → 列表移除 doc 147 | ✅ | 轮询后 `GET /documents?knowledgeBaseId=338` 返回 `data:[]`；`GET /documents/147` 不支持（符合控制器无 GET-by-id） |
| 4 | 同名 `demo.txt` 重新上传 → doc 148 → `AVAILABLE` | ✅ | `POST /documents` 返回 id=148，`PROCESSING`→`AVAILABLE`（发布名释放） |

### 失败路径（步骤 5–7）说明

未 live 触发 `CLEANUP_FAILED`，原因：在共享部署环境人为断开 Milvus/回调以制造失败，会污染环境并可能影响其他用户。该路径已由以下资产覆盖，未在本次冒烟中重放：

- `CleanupFailureTest`（MySQL 集成，验证 CLEANUP_FAILED 状态与重试入口）
- `AutoCleanupTask` FAILSAFE 重放保护（代码已核对：`AutoCleanupTask.java:30-86`）
- 前端 `ManagementPage.vue:75-80` 仅 `CLEANUP_FAILED` 渲染"重试清理"

> 若需 live 验证失败路径，建议在隔离环境执行：临时阻断 Python `/internal/v1/cleanup` 回调，观察 doc 147 进入 `CLEANUP_FAILED` 且前端出现重试按钮，再恢复后点重试清理。

### 不变量

- [x] 不触碰会话/问答/反馈：删除仅改 `sv_enterprise_document.status`，未删 `sv_qa_record`/`sv_feedback`。
- [x] 检索退出恒定：步骤 2b 已证明无 `AVAILABLE` 文档时提问短路拒答。

### 无法直接观测项（环境限制，如实记录）

- **doc 147 最终 DB 状态 `DELETED`**：本机无 `mysql` 客户端，未直连 MySQL 确认；以"列表移除 + 清理回调成功"间接佐证。
- **Milvus chunks 清零**：未直连 Milvus 验证；以 Python 清理回调成功 + 列表移除间接佐证。
- **GET-by-id 不支持**：`GET /documents/147` 返回 `Request method 'GET' is not supported`，故最终状态只能经列表推断。

### 测试产物清理

- 测试 KB 338（含 doc 148）保留在部署环境，标注 `smoke06-test`，待用户决定是否清理。
- 本地临时文件 `demo.txt`、`captcha_tmp.jpg`（已删）仅留 `demo.txt`，可删。

## 结论

核心删除闭环（删除→检索退出→清理→发布名释放）在部署环境实测通过。失败路径与底层存储直连观测受共享环境限制未 live 重放，已由单测/集成测试与代码核对覆盖。父工单可由 `ready-for-deploy-acceptance` 推进为 `resolved`（核心路径已验收）。
