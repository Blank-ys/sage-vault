# 05 — 完成文档失败重试与原子发布

**What to build:** 知识管理员能够理解企业文档的失败原因并在同一记录上整篇重试，普通用户只会检索到完整成功发布的内容，永远不会看到部分结果或新旧片段混合。

**Blocked by:** 02 — 上传并问答一篇 TXT 企业文档.

**Status:** resolved

- [x] 文档清晰呈现处理中、可用、处理失败和删除中状态，只有可用状态参与问答。
  - PROCESSING / AVAILABLE / FAILED 在 `DocumentStatus` 枚举与前端 `ManagementPage.vue` 中完整呈现；Q&A 仅检索向量库中已成功写入的片段（`AnsweringService` 搜索 `vector_store`，失败文档已清理）。
  - DELETING 状态仅在前端 `statusLabel` / `statusType` 中做前向兼容，后端尚无删除流程产生该状态（属于独立工单范围）。
- [x] 解析、切块、嵌入或向量写入任一步失败时，系统清理该次尝试已经产生的片段和向量并保留可诊断的失败原因。
  - `IndexingService.index` 用 try/except 包裹 parse → chunk → embed → save 全链路；失败时 `_cleanup` 调用 `delete_by_document` 清理残留向量；入库前也先 `delete_by_document` 确保原子发布。
  - 失败原因保留在 `errorMessage` 中，包含异常类型与文件名（如 `RAG 入库失败（empty.md）：ValueError`）。
- [x] 知识管理员可对处理失败的记录发起重试；重试保留文档身份和文件名，从头执行整篇处理，不创建重复文档。
  - `RetryRecordWriter.beginRetry` 通过 CAS (`updateStatusIfCurrentStatus`) 将 FAILED → PROCESSING，不插入新文档记录；`attempt` 递增创建新任务。
  - 单元测试 `retryPreservesDocumentIdentityAndFilename` 验证文档 ID、文件名、知识库 ID 不变。
- [x] 重复触发、重复回调和回调乱序不会重复发布、覆盖新结果或产生非法状态转换。
  - 重复触发：CAS 确保仅 FAILED → PROCESSING 成功；对 AVAILABLE/PROCESSING 重试返回 `DOCUMENT_STATE_CONFLICT(410014)`。
  - 重复回调：`IndexingCallbackHandlerImpl` 检查 `isTerminal` + `updateTerminalState` 返回 0 跳过重复。
  - 回调乱序：`request.attempt() < task.getAttempt()` 时忽略 stale 回调。
- [x] 系统验收测试注入各阶段失败并证明失败/重试期间没有部分内容被检索，成功重试后仅存在一套完整片段。
  - 解析阶段：`RetryAndAtomicPublicationSystemTest` 注入空 MD 解析失败，验证失败/重试期间 Q&A 返回"该知识库暂无可用文档"，重试保留文档身份不创建重复文档，AVAILABLE 文档重试返回 410014。
  - 切块/嵌入/向量写入阶段：`StageFailureInjectionSystemTest` 通过适配器层故障注入包装器（`SAGE_VAULT_RAG_TEST_FAILURE_FLAG_FILE` 指向 flag 文件，内容为 `chunk`/`embed`/`vector` 时对应阶段的 `FailureInjectingChunker`/`FailureInjectingEmbedder`/`FailureInjectingVectorStore` 注入 `RuntimeError`），分别验证各阶段失败时 Q&A 仍返回拒答，无部分内容被检索。
  - 成功重试场景：清空 flag 文件后重试 chunk 阶段失败的文档，验证重试保留文档身份与文件名，进入 AVAILABLE，Q&A 返回 `event:completed` 且能检索到文档唯一内容。
  - 仅存在一套完整片段：系统层通过 Q&A 能检索到成功重试文档的唯一标记证明内容完整可检索（`_assert_successful_retry_is_retrievable`）；应用层由 Python 单元测试 `test_index_clears_stale_vectors_before_retry` 验证入库前 `delete_by_document` 清理残留向量后仅保留一套新片段。
  - Python 单元测试补充：`test_index_chunk_failure_triggers_cleanup_and_callback` 与 `test_index_embed_failure_triggers_cleanup_and_callback` 覆盖切块/嵌入阶段失败的清理与回调。
