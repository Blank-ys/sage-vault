# 03 — 扩展 PDF、DOCX、MD 企业文档解析

**What to build:** 知识管理员能够以与 TXT 相同的方式上传带文本层的 PDF、DOCX 和 MD 文件，并在处理成功后通过问答使用其内容；无法提取文本的文件会以可理解的原因失败。

**Blocked by:** 02 — 上传并问答一篇 TXT 企业文档.

**Status:** resolved

- [x] PDF、DOCX、MD 与 TXT 共用同一上传、异步状态和知识库内问答体验，单文件上限为 50 MB。
- [x] 解析优先保留标题和自然段边界，并保存稳定的文档/片段标识、原始文件名、片段顺序及 PDF 页码等未来引用所需元数据。
- [x] 加密、损坏、空白、扫描版 PDF、不可提取文本和不受支持的格式进入处理失败，不发布任何可检索片段，并向知识管理员展示可理解原因。
- [x] 解析器集成测试使用四种成功格式及代表性失败夹具，断言可观察的文本和来源元数据，不绑定具体解析库内部实现。
- [x] 中文文档和中文问答纳入正式验收；英文仅作非承诺性兼容。

## Answer

本工单为父工单，覆盖 03a-03f 六个子工单。所有子工单均已 `resolved`：

| 子工单 | 状态 | 内容 |
| --- | --- | --- |
| [03a](03a-java-upload-accepts-pdf-docx-md.md) | resolved | Java 上传入口接受 PDF/DOCX/MD，扩展名白名单与 MinIO content-type |
| [03b](03b-python-parser-abstraction-and-md.md) | resolved | `DocumentParserPort` 抽象 + `MarkdownParser` 端到端 |
| [03c](03c-python-pdf-parser-and-failures.md) | resolved | `PdfParser` + 加密/损坏/空白扫描版失败语义 |
| [03d](03d-python-docx-parser-and-failures.md) | resolved | `DocxParser` + 损坏/空白失败语义 |
| [03e](03e-chunk-metadata-title-page.md) | resolved | `Chunk`/`RetrievedChunk` 增加 `section_title`/`page_number`，Milvus schema 与 chunker 传播 |
| [03f](03f-parser-integration-tests-chinese-acceptance.md) | resolved | 四种格式整合验收：成功入库 + 失败夹具 + 中文问答 |

### 父工单三项验收

1. **共用上传、异步状态与问答体验，单文件上限 50 MB**：03a 在 [`DocumentFilename`](file:///f:/workspace/ai-coding/sage-vault/backend/ruoyi-kb-management/src/main/java/com/sagevault/kb/document/domain/DocumentFilename.java#L11) 统一放行 TXT/PDF/DOCX/MD 四种扩展名，前端 [`ManagementPage.vue`](file:///f:/workspace/ai-coding/sage-vault/frontend/src/features/enterprise-documents/pages/ManagementPage.vue#L28) `accept` 覆盖四种格式；02 已建立的异步状态机（`PROCESSING → AVAILABLE/FAILED`）与 SSE 问答体验对四种格式无差别。50 MB 上限由 RuoYi 底座 [`FileUploadUtils.DEFAULT_MAX_SIZE`](file:///f:/workspace/ai-coding/sage-vault/backend/ruoyi-modules/ruoyi-file/src/main/java/com/ruoyi/file/utils/FileUploadUtils.java#L29)（`50 * 1024 * 1024L`）与网关 multipart 配置共同强制。

2. **解析器集成测试覆盖四种成功格式与代表性失败夹具**：03f 新增 [`test_parser_format_acceptance.py`](file:///f:/workspace/ai-coding/sage-vault/ai-modules/services/rag/tests/integration/test_parser_format_acceptance.py)，通过生产装配的 `FormatDispatchingDocumentParser`（四种解析器全部注册）参数化验证：4 格式 × 3 维度（入库元数据、search 召回、中文问答）= 12 项成功测试 + 7 项失败夹具测试，共 19 项全过。

3. **中文文档与中文问答正式验收**：所有成功夹具均使用中文内容（"知识库管理办法"、"本办法适用于全体员工"、"知识库按照主题进行分类维护"等），中文问答通过 `AnsweringService` + `FakeGenerationAdapter` 验证 delta 流包含预期中文片段。英文仅作非承诺性兼容，未纳入自动化验收。

### 验证

- Python：`ruff check .` + `mypy .`（83 source files）+ `pytest`（125 passed with Milvus, 88 passed + 37 skipped without Milvus）全过。
- Java：`mvn -f backend/ruoyi-kb-management/pom.xml test` 通过。
- 前端：`yarn --cwd frontend build:prod` 通过。

### 未触及

- 浏览器到 Java 的系统验收（实际上传 PDF/DOCX/MD 并在 UI 问答）不在自动化测试范围；03f 只验证 Python 入库与问答 seam，系统验收需人工执行。
- 现有 dev 环境 Milvus 中的 `sage_vault_chunks` collection schema 需 drop 并重建以加载 03e 新增的 `section_title`/`page_number` 字段（V1 不使用 Flyway/Milvus 迁移框架）。
