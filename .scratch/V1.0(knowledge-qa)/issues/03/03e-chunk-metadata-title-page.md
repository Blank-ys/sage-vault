# 03e — 文档片段元数据扩展：标题与页码

**What to build:** 文档片段携带 `section_title`（章节/标题）和 `page_number`（PDF 页码）等来源元数据，写入 Milvus 并在检索召回时回显，为后续引用溯源打下基础。

**Blocked by:** 03b、03c、03d — 需要三种解析器都能产出结构化元数据。

**Status:** resolved

- [x] `Chunk` / `RetrievedChunk` 增加 `section_title`、`page_number` 字段
- [x] Milvus collection schema 增加对应标量字段
- [x] `save_chunks` 与 `search` 正确读写新字段
- [x] chunker 从 `ParsedParagraph` 传播 `page_number`（PDF）与 `heading`（MD/DOCX）到 `Chunk`
- [x] 检索结果能观察到来源元数据（文件名、顺序、页码/标题）
- [x] 相关单元测试与集成测试通过

## Answer

### 落地范围

- `model/chunk.py`：`Chunk` 追加 `section_title: str | None = None` 与 `page_number: int | None = None`，置于 `text` 之后，保持已有字段顺序不变。
- `model/retrieved_chunk.py`：`RetrievedChunk` 追加同名两字段，置于 `score` 之后；新增字段均有默认值，既有使用关键字 `score=` 的调用无需修改。
- `adapters/chunker/chunker.py`：`ParagraphChunker.split` 在合并自然段为 chunk 时，取首个非空 `heading` 作为 `section_title`、首个非空 `page_number` 作为 `page_number`；超长段落切分后的子 chunk 继承原段落的 `heading`/`page_number`；flush 并重启 chunk 时采用下一段的元数据。新增 `_build_chunk` 私有工厂统一构造 `Chunk`，避免重复参数列表。
- `adapters/milvus/store.py`：
  - `_build_schema` 新增 `section_title`(VARCHAR 512) 与 `page_number`(INT64) 两个标量字段。
  - `save_chunks` 写入两字段。
  - `search` 的 `output_fields` 与 `RetrievedChunk` 构造读取两字段。

### pymilvus 2.4.x nullable 限制与哨兵值方案

pymilvus 2.4.15 的 `FieldSchema` 静默忽略 `nullable=True`（`__init__` 通过 `**kwargs` 接受但不写入 `to_dict()`），导致 collection 以非空字段创建，`collection.insert([None, ...])` 抛 `ParamError: expect string input, got NoneType`。

在项目锁定 `pymilvus>=2.4.10,<2.5` 的约束下，适配器内部采用哨兵值表示"无元数据"，不升级依赖：
- `section_title`：`None` ↔ `""`（空字符串）
- `page_number`：`None` ↔ `0`（页码从 1 开始，0 不会与真实页码冲突）

转换只在 `save_chunks`/`search` 适配器边界发生，`Chunk`/`RetrievedChunk` 模型始终使用 `None`，应用层与跨端契约不感知存储表示。未来升级 pymilvus 后只需修改适配器两处转换即可切换为原生 nullable 字段。

### 验证

- `uv run ruff check .` 全过。
- `uv run mypy .` 全过（82 source files）。
- `uv run pytest`（不含 Milvus）：87 passed, 19 skipped。
- Milvus 集成（`SAGE_VAULT_RAG_RUN_MILVUS_TESTS=1`，host=192.168.150.100:19530，bge-m3 cpu-dev）：
  - 新增 `tests/integration/milvus/test_milvus_writer.py` 2 项元数据 round-trip 测试：带元数据片段 search 回显 `section_title`/`page_number`；无元数据片段（TXT）search 返回 `None`。
  - MD/DOCX/PDF 端到端测试新增 `vector_store.search(...)` 断言：MD/DOCX 召回片段 `section_title` 非空且 `page_number is None`；PDF 召回片段 `page_number == 1` 且 `section_title is None`。
  - 新增 `tests/unit/adapters/test_chunker.py` 7 项元数据传播测试（合并取首个非空、超长段切分继承、flush 重启采用新元数据、TXT 默认 None）。
  - 新增 `tests/unit/application/test_indexing.py` 1 项测试：MD 入库后 `InMemoryVectorStore.saved` 中的 `Chunk` 携带 `section_title`。
  - 完整测试套件 106 passed, 0 skipped, 0 failed。

### 未触及

- `IndexingResult` 与 SSE 事件不变（chunk 元数据是 Python 内部检索/溯源信息，不进入跨端 wire contract）。
- Java 侧不变（Java 不直连 Milvus，也不感知 chunk 字段）。
- 解析器（PDF/MD/DOCX/TXT）不变：03b/03c/03d 已在 `ParsedParagraph` 产出 `heading`/`page_number`，本工单只负责从 `ParsedParagraph` 传播到 `Chunk` 并持久化。
- 现有 dev 环境 Milvus 中的 `sage_vault_chunks` collection schema 不含新字段，部署本工单代码后需 drop 并重建 collection（V1 不使用 Flyway/Milvus 迁移框架，schema 演进为人工操作）。
- 解析器集成测试覆盖四种格式与代表性失败夹具留给 03f。
