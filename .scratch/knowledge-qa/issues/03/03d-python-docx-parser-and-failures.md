# 03d — Python DOCX 解析与可理解失败

**What to build:** DOCX 文件可被解析、切块、入库和问答；损坏或空白 DOCX 进入 `FAILED` 状态并展示具体原因。

**Blocked by:** 03b — 需要统一的解析器接口。

**Status:** resolved

- [x] 实现 `DocxParser`，保留段落与标题层级
- [x] 损坏 DOCX 返回可理解的失败原因
- [x] 空白 DOCX 返回“文档内容为空”
- [x] 解析失败时不向 Milvus 写入任何片段，回调 Java 的 `FAILED` 状态
- [x] 集成测试使用成功与失败 DOCX fixture，只断言可观察文本和来源元数据
- [x] Python ruff、mypy、pytest 通过

## Answer

### 落地范围

- 新增 `adapters/docx_parser/parser.py`：`DocxParser` 实现 `DocumentParserPort`，使用 python-docx 按文档顺序遍历 `document.paragraphs`。
  - 样式为 `Heading 1` ~ `Heading 6` 的段落作为标题段输出，`text` 与 `heading` 均为标题文本，并成为后续正文段的当前标题；Heading 7~9 在 Word 默认 UI 中不暴露且与 Markdown ATX 六级标题对齐，故不识别。
  - 非标题段落输出 `text` 为正文，`heading` 为最近一个标题文本（无标题时为 `None`）。
  - DOCX 无页码概念，`page_number` 始终为 `None`（与 TXT/MD 一致，区别于 PDF）。
- 失败语义按工单要求映射为 `ValueError`，由 `IndexingService` 统一走既有清理与回调路径：
  - 损坏或无法识别的 DOCX（非 ZIP 或非 OOXML 包，捕获 `BadZipFile`/`PackageNotFoundError`/段落读取 `KeyError`/`IndexError`）抛 `ValueError("文件损坏，无法解析: <filename>")`。
  - 空内容或有效但无可提取文本的 DOCX 抛 `ValueError("文档内容为空: <filename>")`。
- 在 `transport/http/app.py` 的 `build_indexing_service` 中注册 `"docx": DocxParser()` 到 `FormatDispatchingDocumentParser`。
- 在 `pyproject.toml` 新增 `python-docx>=1.2,<2`（`docs/technology-stack.md` 已预登记该版本范围）。
- 新增 `tests/_docx_fixtures.py`：使用 python-docx 低级 API 生成测试夹具（单段、多段、带标题层级、有效但空、仅空白、带前导正文），无需引入额外测试依赖。

### 验证

- `uv run ruff check .` 全过。
- `uv run mypy .` 全过（82 source files）。
- `uv run pytest`（不含 Milvus）：79 passed, 16 skipped。
- Milvus 集成（`SAGE_VAULT_RAG_RUN_MILVUS_TESTS=1`，host=192.168.150.100:19530，bge-m3 cpu-dev）：
  - 新增 `tests/integration/test_docx_indexing_end_to_end.py` 4 项全过（含"上传 DOCX 后问答召回中文内容"、"空白 DOCX 入库失败不写入片段"、"损坏 DOCX 入库失败不写入片段"的端到端断言）。
  - 完整测试套件 96 passed, 0 skipped, 0 failed。

### 代码评审后修正

- `_extract_paragraphs` 的 if/else 两支 `append` 完全相同，合并为先更新 `current_heading` 再统一 append。
- `except Exception` 收窄为 `except (KeyError, IndexError)`，避免把 python-docx 内部 bug 误报为文件损坏。
- Heading 1~6 限制在 docstring 中注明理由（Word 默认 UI 不暴露 7~9，与 Markdown ATX 六级对齐）。
- 新增 `test_corrupted_docx_indexing_returns_failure_without_writing_chunks` 端到端测试，覆盖损坏 DOCX 走完整失败路径（原先只覆盖了空白 DOCX）。

### 未触及

- `Chunk`/`RetrievedChunk` 模型与 Milvus schema 不变（`section_title`/`page_number` 留给 03e）。
- Java 上传链路不变（`.docx` 已由 03a 在 `DocumentFilename` 放行）。
- `IndexingResult.diagnostics` 仍只记录异常类名与文件名，不向 Java 暴露异常消息（与 PDF/MD/TXT 行为一致，由 03f 或后续工单统一裁定）。
- 测试脚手架（`InMemoryDocumentStorage`/`InMemoryCallback`/Milvus fixture）跨格式重复为已知模式，与既有 PDF/MD/TXT 端到端测试一致，不在本工单抽取。
