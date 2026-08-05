# 03f — 解析器集成测试与中文验收

**What to build:** 用四种成功格式（TXT/PDF/DOCX/MD）及代表性失败夹具覆盖完整入库链路；断言可观察的文本内容和来源元数据，不绑定具体解析库内部实现；最终用中文文档和中文问答完成正式验收。

**Blocked by:** 03a、03b、03c、03d、03e — 需要上传入口、全部解析器、元数据扩展都就绪。

**Status:** resolved

- [x] 集成测试覆盖 TXT/PDF/DOCX/MD 四种成功入库
- [x] 失败夹具覆盖加密/损坏/空白/扫描版 PDF、损坏/空白 DOCX、空 MD
- [x] 断言召回文本包含预期中文内容
- [x] 断言来源元数据（文件名、片段顺序、页码/标题）
- [x] 中文问答验证各格式文档内容可被召回并回答
- [x] Java 后端测试、Python ruff/mypy/pytest、前端 `build:prod` 均通过

## Answer

### 落地范围

- 新增 [`tests/integration/test_parser_format_acceptance.py`](file:///f:/workspace/ai-coding/sage-vault/ai-modules/services/rag/tests/integration/test_parser_format_acceptance.py)：03f 的唯一交付物，一个参数化的"四种格式整合验收"集成测试文件，共 19 项测试用例。
- 测试通过生产装配的 `FormatDispatchingDocumentParser`（TXT/MD/PDF/DOCX 四种解析器全部注册）执行，确保 03a-03e 的解析器、元数据扩展与切块器在统一分发路径下协同工作。
- 不新增任何生产代码；03f 是纯测试工单，验证既有解析器与入库链路的端到端契约。

### 测试组织

**成功入库（4 格式 × 3 断言维度 = 12 项参数化测试）：**

1. `test_four_formats_indexing_succeeds_and_publishes_chinese_chunks[txt|md|pdf|docx]`
   - 断言 `IndexingResult.success is True`、`chunks_count >= 1`、Milvus `count_by_document` 一致
   - 直接读取 collection 行断言来源元数据：`filename` 一致、`sequence` 从 0 开始连续、`section_title`/`page_number` 哨兵值正确
   - 断言召回文本包含预期中文内容（如"本办法适用于全体员工"、"知识库按照主题进行分类维护"）
2. `test_four_formats_search_returns_source_metadata[txt|md|pdf|docx]`
   - 通过 `vector_store.search` port 验证召回片段携带正确元数据
   - TXT/PDF：所有片段 `section_title is None`；PDF `page_number == 1`，TXT `page_number is None`
   - MD/DOCX：至少一个片段 `section_title` 等于预期标题（多标题文档不同片段标题可能不同）
3. `test_four_formats_chinese_qa_recalls_and_answers[txt|md|pdf|docx]`
   - 用 `AnsweringService` + `FakeGenerationAdapter` 验证中文问答流式回答
   - 断言 `Completed` 事件、delta 拼接后包含预期中文内容

**失败夹具（7 项参数化测试）：**

`test_failure_fixtures_return_failed_without_writing_chunks` 覆盖：

| 夹具名 | 格式 | 失败模式 |
| --- | --- | --- |
| `encrypted_pdf` | PDF | 加密（空密码无法解密） |
| `corrupted_pdf` | PDF | 损坏（非 PDF 字节） |
| `blank_pdf` | PDF | 空白（空字节 `b""`） |
| `scanned_pdf` | PDF | 扫描版（有效 PDF 但无文本层，`make_blank_pdf()`） |
| `corrupted_docx` | DOCX | 损坏（非 DOCX 字节） |
| `blank_docx` | DOCX | 空白（有效 DOCX 但无段落文本，`make_empty_docx()`） |
| `empty_md` | MD | 空（空字节 `b""`） |

每项断言：`success is False`、`chunks_count == 0`、`diagnostics["error"] == "ValueError"`、`diagnostics["filename"]` 匹配、Milvus 无片段写入、回调被调用且只携带该结果。

### 代码评审后修正

- **拆分空白与扫描版 PDF 夹具**：原 `blank_scanned_pdf` 合并了两种失败模式；评审指出父工单 03 将"空白"与"扫描版"列为独立场景。拆分为 `blank_pdf`（空字节）与 `scanned_pdf`（有效 PDF 无文本层），覆盖 pypdf 的两条不同失败路径。
- **入库测试补充元数据断言**：原 `test_four_formats_indexing_succeeds...` 只断言 `filename` 与 `sequence`，将 `section_title`/`page_number` 推迟到 search 测试。评审指出规格要求"断言来源元数据（文件名、片段顺序、页码/标题）"应在入库 seam 也验证。补充行级 `section_title`/`page_number` 哨兵值断言（Milvus 2.4.x 用 `""`/`0` 表示 `None`）。
- **移除冗余 `page_number` 断言**：search 测试中对所有召回片段先强制 `page_number == expected`、随后又按分支重新断言，对多页文档会误判。移除冗余的强制相等行，保留按格式分支的显式校验。
- **重命名 `_index_success` → `_index_fixture`**：原名称暗示断言 success，但函数只执行入库并返回结果。重命名为 `_index_fixture` 并补充 docstring 说明"不断言 success，由调用方断言"。

### 验证

- `uv run ruff check .` 全过。
- `uv run mypy .` 全过（83 source files）。
- `uv run pytest`（不含 Milvus）：88 passed, 37 skipped。
- Milvus 集成（`SAGE_VAULT_RAG_RUN_MILVUS_TESTS=1`，host=192.168.150.100:19530，bge-m3 cpu-dev）：
  - 新增 `tests/integration/test_parser_format_acceptance.py` 19 项全过（4 格式 × 3 维度 + 7 失败夹具）。
  - 完整测试套件 125 passed, 0 skipped, 0 failed。
- Java 后端：`mvn -f backend/ruoyi-kb-management/pom.xml test` 通过（exit code 0，03a 已验证 59 tests）。
- 前端：`yarn --cwd frontend build:prod` 通过。

### 未触及

- 不新增生产代码或 port 方法；`MilvusVectorStore` 的私有成员访问（`_get_collection`/`_document_expr`）与 03b-03e 既有端到端测试一致，属于存储适配器边界内的可观察行为。
- 不抽取跨文件共享测试脚手架（`InMemoryDocumentStorage`/`InMemoryCallback`/Milvus fixture）；与既有 PDF/MD/DOCX/TXT 端到端测试的重复模式一致，保持测试独立性。
- 浏览器到 Java 的系统验收不在 03f 范围；03f 只验证 Python 入库与问答 seam。
