# Handoff: 06a — 文档删除 Happy Path 实现

## 当前状态

正在实现工单 `06a-delete-happy-path-retrieval-exit-cleanup-name-release.md`。已完成契约层修改（openapi.yaml），其余 Java/Python/前端尚未开始编码。

## 工单与规格

- 父工单: `.scratch/knowledge-qa/issues/06/06-delete-document-and-release-name.md`
- **实施工单**: `.scratch/knowledge-qa/issues/06/06a-delete-happy-path-retrieval-exit-cleanup-name-release.md`
- 状态: `ready-for-agent`，blocked by 05（已 resolved）

## 已完成的工作

### 1. 契约层 (contracts/java-python-rag/v1/openapi.yaml)
已新增两个 endpoint 和对应 schema：
- `POST /internal/v1/cleanup` — Java → Python 清理命令（CleanupCommand: taskId, knowledgeBaseId, documentId, requestId）
- `POST /internal/v1/cleanup/callbacks` — Python → Java 清理回调（CleanupCallback: taskId, documentId, success, requestId, diagnostics?）

### 2. 尚未创建契约 examples
`contracts/java-python-rag/v1/examples/` 下还需添加 `cleanup-command.json` 和 `cleanup-callback.json`。

## 待完成任务清单（按顺序）

| # | 任务 | 状态 |
|---|------|------|
| 1 | 契约 examples 补充 | 待做 |
| 2 | Java: `DocumentStatus` 添加 `DELETING("删除中")`；`ErrorCode` 添加 `CLEANUP_DISPATCH_FAILED(510005)`, `CLEANUP_CALLBACK_INVALID(410015)` | 待做 |
| 3 | Java: `DocumentMapper` 添加 `deleteById(long id)` + XML | 待做 |
| 4 | Java: `DocumentStorage` port 添加 `deleteByPrefix(String prefix)`；`MinioDocumentStorage` 实现（listObjects + removeObject） | 待做 |
| 5 | Java: 新建 `service/port/CleanupCommandDispatcher.java`；新建 `adapter/rag/DiscoveredRagCleanupAdapter.java`（仿 `DiscoveredRagIndexingAdapter` 签名模式） | 待做 |
| 6 | Java: `DocumentService` 添加 `void delete(long documentId)`；`DocumentServiceImpl` 实现 CAS(AVAILABLE→DELETING) + dispatch cleanup | 待做 |
| 7 | Java: 新建 `domain/CleanupCallbackRequest.java` record；新建 `service/CleanupCallbackHandler.java` interface + `impl/CleanupCallbackHandlerImpl.java`（成功→deleteByPrefix+deleteById；失败→记错误日志） | 待做 |
| 8 | Java: 新建 `controller/CleanupCallbackController.java`（路径 `/internal/v1/cleanup/callbacks`，签名验证仿 IndexingCallbackController） | 待做 |
| 9 | Java: `DocumentController` 添加 `@DeleteMapping("/{id}")` + `@RequiresPermissions("sage:document:manage")` | 待做 |
| 10 | Python: 新建 `model/cleanup_result.py`（dataclass: task_id, document_id, success, diagnostics） | 待做 |
| 11 | Python: 新建 `application/cleanup/__init__.py` + `service.py`（CleanupService: vector_store.delete_by_document → callback） | 待做 |
| 12 | Python: `transport/http/app.py` 添加 `POST /internal/v1/cleanup` endpoint（202, BackgroundTasks）+ 签名验证 | 待做 |
| 13 | Python: `adapters/java_callback/callback.py` 扩展 `report_cleanup(result)` 方法；`bootstrap/settings.py` 添加 `java_cleanup_callback_url` | 待做 |
| 14 | Frontend: `api/documents.js` 添加 `deleteDocument(id)`；`ManagementPage.vue` 添加删除按钮+确认弹窗+DELETING 状态标签（标签已存在） | 待做 |
| 15 | 验证: Maven compile、frontend build:prod、Python ruff/mypy | 待做 |

## 关键设计决策

1. **CAS 删除**: `updateStatusIfCurrentStatus(id, "DELETING", "", "AVAILABLE")` 返回 0 行时抛 `DOCUMENT_STATE_CONFLICT`。
2. **名称释放**: 清理成功后 Java 物理删除 DB 记录（`deleteById`），唯一约束 `uk_sv_enterprise_document_kb_normalized_name` 自然释放。
3. **MinIO 清理**: 从 `objectKey` 截取前缀（`documents/{kbId}/{uuid}/`），调用 `deleteByPrefix` 删除原文件及解析产物。
4. **Python 清理幂等**: `MilvusVectorStore.delete_by_document` 已是幂等 delete expr。
5. **无 cleanup task 表**: Happy path 不建表，taskId 仅用于签名/追踪；06b 再补 scheduler 恢复。
6. **检索退出**: DELETING 文档的向量在 Python cleanup 完成后物理消失；系统验收在 cleanup 回调后断言不可检索。

## 关键文件路径

### Java（backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/）
- `document/domain/DocumentStatus.java` — 枚举（当前: PROCESSING, AVAILABLE, FAILED）
- `document/domain/DocumentEntity.java` — 实体
- `document/mapper/DocumentMapper.java` + `resources/mapper/document/DocumentMapper.xml`
- `document/service/DocumentService.java` / `impl/DocumentServiceImpl.java`
- `document/service/port/DocumentStorage.java` / `adapter/MinioDocumentStorage.java`
- `document/service/port/IndexingCommandDispatcher.java` / `adapter/rag/DiscoveredRagIndexingAdapter.java`（签名模式参考）
- `document/controller/IndexingCallbackController.java`（回调签名验证参考）
- `platform/error/ErrorCode.java`
- `bootstrap/RagProperties.java` — record(serviceId, signingKey)

### Python（ai-modules/services/rag/src/sage_vault_rag/）
- `transport/http/app.py` — FastAPI 路由 + 签名验证
- `adapters/milvus/store.py` — `delete_by_document` 已存在
- `adapters/java_callback/callback.py` — 签名回调客户端
- `bootstrap/settings.py` — pydantic-settings（env_prefix=SAGE_VAULT_RAG_）
- `ports/vector_store.py` — VectorStorePort protocol
- `ports/callback.py` — CallbackPort protocol

### Frontend（frontend/src/features/enterprise-documents/）
- `api/documents.js`
- `pages/ManagementPage.vue`（DELETING 状态标签已预埋）

## 基础设施（用户提供，凭据已脱敏）

- MySQL: `192.168.150.100:3306/ry-cloud`
- MinIO: `192.168.150.100:9000`
- Milvus: `192.168.150.100:9091`（注意：Python settings 默认 port 19530，实际 gRPC 端口需确认）
- 本地 Maven 仓库: `F:\environment\maven-repository`

## 编码约束提醒

- Java: 构造器注入、BusinessException(ErrorCode.XXX, "msg")、Mapper XML 不用注解/SELECT *、record 做 Request/Response
- Python: Pydantic transport 类型只在 transport/http；application 只依赖 model+ports；签名用 hmac
- Frontend: Vue 3 + JS + Element Plus；不引入 TS；feature 内闭环
- 契约: v1 只允许兼容性新增
- 签名模式: Java→Python 用 `RagProperties.signingKey`；Python→Java 用 `java_callback_signing_key`

## Suggested Skills

- `/implement` — 继续实现剩余任务
- `/tdd` — 如需为 cleanup callback handler 或 Python CleanupService 写测试
- `/code-review` — 实现完成后审查变更
- `/diagnosing-bugs` — 如编译/运行时遇到问题
