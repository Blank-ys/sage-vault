# 03b — Python 解析器抽象与 Markdown 端到端支持

**What to build:** Python RAG 内出现统一的 `DocumentParserPort`（替代当前的 `TextParserPort`），并首个实现 `MarkdownParser`；管理员上传 `.md` 文件后，系统能解析、切块、嵌入、写入 Milvus，最终用户可在问答中召回其内容。

**Blocked by:** None — 不依赖 PDF/DOCX 解析器。

**Status:** resolved

- [x] 定义 `DocumentParserPort`，返回结构化文档（段落列表 + 可选标题/页码元数据）
- [x] 实现 `MarkdownParser`，保留标题与自然段边界，空文档返回可理解的失败原因
- [x] `IndexingService` 改用新 port，失败时仍走现有清理与回调路径
- [x] `.md` 文件上传 → 状态变为 `AVAILABLE` → 问答能召回中文内容
- [x] 解析器单元测试只断言可观察文本和元数据，不绑定具体解析库内部实现
- [x] Python ruff、mypy、pytest 通过

## Answer

### 落地范围

- 新增 `model/parsed_document.py`：`ParsedParagraph(text, heading, page_number)` 与 `ParsedDocument(paragraphs)`，作为解析器→切块器的统一结构化契约。`heading`/`page_number` 为可选来源元数据，本工单不写入 `Chunk`（由 03e 接管）。
- 新增 `ports/document_parser.py`：`DocumentParserPort.parse(content, filename) -> ParsedDocument`，替代 `TextParserPort`；旧 port 文件已删除。
- 重写 `adapters/txt_parser/parser.py`：`TxtParser` 返回 `ParsedDocument`，按双换行切段；保留 02 工单既有契约（空内容返回空文档，编码不可靠时抛 `ValueError`）。
- 新增 `adapters/markdown_parser/`：`MarkdownParser` 识别 ATX 标题（`#`~`######`），标题作为独立 `ParsedParagraph` 输出并作为后续正文段的 `heading` 元数据；空文档或仅含空白时抛 `ValueError("无法从 Markdown 文件中提取任何文本内容: <filename>")`；支持 UTF-8 BOM 与 CRLF 归一化。
- 新增 `adapters/document_parser/dispatcher.py`：`FormatDispatchingDocumentParser` 按扩展名分发，未注册扩展名抛 `ValueError`，便于 03c/03d 后续注册 PDF/DOCX。
- 更新 `ports/chunker.py` 与 `adapters/chunker/chunker.py`：`ChunkerPort.split` 接收 `ParsedDocument` 而非 `str`，`ParagraphChunker` 直接迭代 `document.paragraphs` 合并/切分，标题段天然保留在 chunk 文本中。
- 更新 `application/indexing/service.py`：`IndexingService` 改注入 `document_parser: DocumentParserPort`，失败仍走既有 Milvus 清理 + 回调路径，`diagnostics` 仅记录异常类型与文件名。
- 更新 `transport/http/app.py`：`build_indexing_service` 装配 `FormatDispatchingDocumentParser({"txt": TxtParser(), "md": MarkdownParser()})`。

### 验证

- `uv run ruff check .` 全过。
- `uv run mypy .` 全过（69 source files）。
- `uv run pytest`（不含 Milvus）：46 passed, 10 deselected。
- Milvus 集成（`SAGE_VAULT_RAG_RUN_MILVUS_TESTS=1`，host=192.168.150.100:19530，bge-m3 cpu-dev）：
  - 新增 `tests/integration/test_md_indexing_end_to_end.py` 2 项全过（含"上传 MD 后问答召回中文内容"的端到端断言）。
  - 既有 TXT 端到端、检索与 Milvus 写入 8 项全过，未回归。

### 代码评审后修正

- `MarkdownParser` 增加编码可靠性检查（低置信度 + 替换字符时抛 `ValueError`），与 `TxtParser` 行为一致，避免静默降级产出乱码片段。
- `ParsedParagraph.heading` 仅保留标题文本（如 `知识库管理办法`），去掉 ATX `#` 前缀，便于 03e 直接写入 `Chunk.section_title`；`text` 仍保留完整 ATX 语法供嵌入/检索。
- `test_index_md_failure_triggers_cleanup_and_callback` 改用 `FormatDispatchingDocumentParser` 装配，与生产路径一致。

### 未触及

- `Chunk`/`RetrievedChunk` 模型与 Milvus schema 不变（`section_title`/`page_number` 留给 03e）。
- Java 上传链路不变（`.md` 已由 03a 在 `DocumentFilename` 放行）。
- PDF/DOCX 解析器不实现（03c/03d）。
- 多行块中第一行是标题但无空行分隔的边缘情况（`# 标题\n正文`）按正文段处理，作为已知简化保留。
