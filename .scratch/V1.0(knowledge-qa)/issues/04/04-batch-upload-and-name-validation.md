# 04 — 实现批量上传与同名原子校验

**What to build:** 知识管理员能够一次选择多篇企业文档上传，并在任何文件名冲突时得到整批、完整且无副作用的拒绝；校验通过后每篇文档独立处理。

**Blocked by:** 03 — 扩展 PDF、DOCX、MD 企业文档解析.

**Status:** resolved

- [x] 上传前同时检查本批文件之间以及目标知识库现有记录中的文件名冲突，比较不区分大小写。
- [x] 处理中、可用和处理失败记录都占用文件名；不同知识库允许相同文件名。
- [x] 任一冲突会拒绝整个请求、一次列出所有冲突文件，且不创建业务记录、不写入 MinIO、不触发异步处理。
- [x] 整批校验通过后，每篇文件建立独立状态和异步任务；一篇后续处理失败不回滚其他文件。
- [x] 页面在上传前展示问题和召回片段将发送至阿里云百炼的提示，但不增加审批、敏感等级或强制确认勾选。

## 实现落点

### 后端

- `DocumentMapper.java` / `DocumentMapper.xml`：新增 `findByKbIdAndNormalizedNames(kbId, normalizedNames)` 批量查询，按 `kb_id` + `normalized_name IN (...)` 检索占用文件名的记录，覆盖处理中、可用、失败全部状态。
- `DocumentRecordWriter.java` / `DocumentRecordWriterImpl.java`：新增 `validateBatch(knowledgeBaseId, files)`：
  - 先用 `KnowledgeBaseService.requireAvailable` 拒绝不可用知识库；
  - 用 `DocumentFilename.of(...).normalizedValue()` 做大小写不敏感规范化；
  - 本批内重复与数据库已存在记录的冲突文件名合并到 `LinkedHashSet`，一次性抛 `BusinessException(DOCUMENT_FILENAME_CONFLICT, "以下文件名在知识库内或本批中已存在：...")`，无任何写入或异步派发。
- `DocumentService.java` / `DocumentServiceImpl.java`：新增 `uploadBatch(knowledgeBaseId, files)`：先调 `validateBatch` 原子校验，再循环 `uploadOne` 独立处理每篇（创建记录 → 写 MinIO → 创建索引任务 → 派发），单篇失败仅 `markFailed` 该篇，不影响其他文件。
- `DocumentController.java`：新增 `POST /documents/batch`，`@RequiresPermissions("sage:document:manage")`，接收 `knowledgeBaseId` 与 `MultipartFile[] files`，返回 `R<List<DocumentResponse>>`。
- `application.yml`：`max-request-size` 调整到 250MB 以容纳批量上传请求体，单文件仍保持 50MB 上限。

### 前端

- `api/documents.js`：新增 `uploadDocuments(knowledgeBaseId, files)`，使用 `FormData` 把 `knowledgeBaseId` 与多个 `files` 字段一起 POST 到 `/ruoyi-kb-management/documents/batch`。
- `pages/ManagementPage.vue`：
  - 顶部 `el-alert` 展示阿里云百炼提示，无审批/敏感等级/确认勾选；
  - `el-upload` 改为 `multiple` + `auto-upload="false"` + 文件列表展示；
  - 新增"开始上传"按钮触发 `startBatchUpload`，提交前校验扩展名与 50MB 单文件上限；
  - 上传成功后清空文件列表并刷新文档表格。

### 测试

- `DocumentRecordWriterImplTest`：覆盖本批重复、跨大小写冲突、与 DB 已有记录冲突、空批、不可用知识库等场景。
- `DocumentServiceImplTest`：覆盖批量校验通过后逐篇独立处理、单篇失败不影响其他文件。
- `DocumentAuthorizationTest`：覆盖 `/documents/batch` 的权限与基本参数校验。
- `DocumentMapperMySqlIntegrationTest`：覆盖 `findByKbIdAndNormalizedNames` 在 MySQL 下的真实查询行为。
- `BusinessExceptionHandlerTest`：补充 `MaxUploadSizeExceededException` 用例。

## 边界说明

- **空 `.txt` 文件**：前端扩展名校验通过，后端按 0 字节文件正常创建 `PROCESSING` 记录。RAG 解析阶段 `TxtParser` 对空内容返回 `ParsedDocument(paragraphs=[])`，`ParagraphChunker` 生成 0 个 chunk，`IndexingService` 最终返回 `success=true, chunks_count=0`，文档状态变为 `COMPLETED`。**空 TXT 不算解析失败**，也不影响同批其他文件的处理。
- 如需在页面上验证"单篇失败不回滚其他文件"，应使用损坏的 PDF、空 `.md`/`.docx` 或其他无法提取文本/段落的文件作为失败样本。
