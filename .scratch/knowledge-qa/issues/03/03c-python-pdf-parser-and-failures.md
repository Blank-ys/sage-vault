# 03c — Python PDF 解析与可理解失败

**What to build:** 带文本层的 PDF 可被解析、切块、入库和问答；加密、损坏、空白、扫描版 PDF 或无法提取文本的 PDF 进入 `FAILED` 状态，并向管理员展示具体原因。

**Blocked by:** 03b — 需要统一的解析器接口。

**Status:** resolved

- [x] 实现 `PdfParser`，提取文本并记录页码
- [x] 加密 PDF 返回"文件已加密，无法解析"
- [x] 损坏 PDF 返回"文件损坏，无法解析"
- [x] 空白或扫描版 PDF 返回"未检测到可提取文本"
- [x] 解析失败时不向 Milvus 写入任何片段，回调 Java 的 `FAILED` 状态
- [x] 集成测试使用成功与失败 PDF fixture，只断言可观察文本和来源元数据
- [x] Python ruff、mypy、pytest 通过

## Answer

### 落地范围

- 新增 `adapters/pdf_parser/parser.py`：`PdfParser` 实现 `DocumentParserPort`，使用 pypdf 按页提取文本，每个段落携带 `page_number` 元数据（从 1 开始）；`heading` 始终为 `None`（PDF 文本层无章节结构信息）。
- 失败语义按工单要求映射为 `ValueError`，由 `IndexingService` 统一走既有清理与回调路径：
  - 加密 PDF（空密码无法解密）抛 `ValueError("文件已加密，无法解析: <filename>")`。
  - 损坏或无法识别的 PDF 抛 `ValueError("文件损坏，无法解析: <filename>")`。
  - 空白或扫描版 PDF（无文本层）抛 `ValueError("未检测到可提取文本: <filename>")`。
- 空密码加密的 PDF（DRM 保护但允许阅读）能正常解密并提取文本，不误报为加密失败。
- 在 `transport/http/app.py` 的 `build_indexing_service` 中注册 `"pdf": PdfParser()` 到 `FormatDispatchingDocumentParser`。
- 新增 `tests/_pdf_fixtures.py`：使用 pypdf 低级 API 生成测试 PDF 夹具（带文本层、加密、空白），无需引入额外测试依赖；使用 Type0 字体（STSong-Light + UniGB-UCS2-H CMap）支持中文文本提取。

### 验证

- `uv run ruff check .` 全过。
- `uv run mypy .` 全过（75 source files）。
- `uv run pytest`（不含 Milvus）：62 passed, 13 skipped。
- Milvus 集成（`SAGE_VAULT_RAG_RUN_MILVUS_TESTS=1`，host=192.168.150.100:19530，bge-m3 cpu-dev）：
  - 新增 `tests/integration/test_pdf_indexing_end_to_end.py` 3 项全过（含"上传 PDF 后问答召回中文内容"和"加密 PDF 入库失败不写入片段"的端到端断言）。
  - 既有 TXT/MD 端到端、检索与 Milvus 写入 10 项全过，未回归。
  - 完整测试套件 75 passed, 0 skipped, 0 failed。

### 未触及

- `Chunk`/`RetrievedChunk` 模型与 Milvus schema 不变（`section_title`/`page_number` 留给 03e）。
- Java 上传链路不变（`.pdf` 已由 03a 在 `DocumentFilename` 放行，03a 尚未实现）。
- DOCX 解析器不实现（03d）。
- 解析器集成测试覆盖四种格式与代表性失败夹具留给 03f。
