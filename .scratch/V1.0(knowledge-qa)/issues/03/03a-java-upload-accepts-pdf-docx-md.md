# 03a — Java 上传入口接受 PDF/DOCX/MD

**What to build:** 知识管理员在同样的上传组件中可以选择 `.pdf`、`.docx`、`.md` 文件；Java 校验扩展名白名单并放行合法文件，原文件进入 MinIO 时带上正确的 MIME 类型，非法扩展名仍被明确拒绝。

**Blocked by:** None — 可在 02 基础上立即开始。

**Status:** resolved

- [x] 前端 `accept` 与按钮文案支持 TXT/PDF/DOCX/MD 四种格式
- [x] `DocumentFilename` 扩展名白名单新增 pdf、docx、md，同时保留 txt
- [x] `DocumentServiceImpl` 按实际文件类型设置 MinIO content-type
- [x] 非法扩展名仍返回明确错误，文件名唯一性校验逻辑不变
- [x] 上传后文档记录状态为 `PROCESSING`，接口立即返回
- [x] Java 模块测试、HTTP 上传接口测试通过；前端 `build:prod` 通过

## Answer

### 落地范围

- 前端 [`ManagementPage.vue`](file:///f:/workspace/ai-coding/sage-vault/frontend/src/features/enterprise-documents/pages/ManagementPage.vue#L28)：`accept=".txt,.pdf,.docx,.md"`，`allowedExtensions` 白名单与提示文案均为 TXT/PDF/DOCX/MD 四种格式。
- 后端 [`DocumentFilename`](file:///f:/workspace/ai-coding/sage-vault/backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/document/domain/DocumentFilename.java#L11)：`CONTENT_TYPES` 已包含四种扩展名映射，非法扩展名抛 `BusinessException(ErrorCode.INVALID_REQUEST, "仅支持上传 TXT、PDF、DOCX、MD 文件")`。
- 后端 [`DocumentServiceImpl.storeOriginal()`](file:///f:/workspace/ai-coding/sage-vault/backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/document/service/impl/DocumentServiceImpl.java#L57) 通过 `DocumentFilename.contentType()` 按实际扩展名设置 MinIO content-type。
- 后端 [`DocumentRecordWriterImpl.entity()`](file:///f:/workspace/ai-coding/sage-vault/backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/document/service/impl/DocumentRecordWriterImpl.java#L55) 创建文档时状态固定为 `PROCESSING`；文件名唯一性校验逻辑未变。

### 验证

- Java 模块测试：`mvn -f backend/ruoyi-kb-management/pom.xml test` 通过（59 tests, 0 failures, 13 skipped）。
- 前端构建：`yarn --cwd frontend build:prod` 通过。

### 未触及

- DOCX 解析器在 Python 侧尚未实现（03d），上传入口仅负责接收与存储原文件。
