# 03e — 文档片段元数据扩展：标题与页码

**What to build:** 文档片段携带 `section_title`（章节/标题）和 `page_number`（PDF 页码）等来源元数据，写入 Milvus 并在检索召回时回显，为后续引用溯源打下基础。

**Blocked by:** 03b、03c、03d — 需要三种解析器都能产出结构化元数据。

**Status:** ready-for-agent

- [ ] `Chunk` / `RetrievedChunk` 增加 `section_title`、`page_number` 字段
- [ ] Milvus collection schema 增加对应标量字段
- [ ] `save_chunks` 与 `search` 正确读写新字段
- [ ] PDF 解析器为片段填充页码，MD/DOCX 解析器为片段填充标题
- [ ] 检索结果能观察到来源元数据（文件名、顺序、页码/标题）
- [ ] 相关单元测试与集成测试通过
