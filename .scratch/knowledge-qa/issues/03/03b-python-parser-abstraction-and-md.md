# 03b — Python 解析器抽象与 Markdown 端到端支持

**What to build:** Python RAG 内出现统一的 `DocumentParserPort`（替代当前的 `TextParserPort`），并首个实现 `MarkdownParser`；管理员上传 `.md` 文件后，系统能解析、切块、嵌入、写入 Milvus，最终用户可在问答中召回其内容。

**Blocked by:** None — 不依赖 PDF/DOCX 解析器。

**Status:** ready-for-agent

- [ ] 定义 `DocumentParserPort`，返回结构化文档（段落列表 + 可选标题/页码元数据）
- [ ] 实现 `MarkdownParser`，保留标题与自然段边界，空文档返回可理解的失败原因
- [ ] `IndexingService` 改用新 port，失败时仍走现有清理与回调路径
- [ ] `.md` 文件上传 → 状态变为 `AVAILABLE` → 问答能召回中文内容
- [ ] 解析器单元测试只断言可观察文本和元数据，不绑定具体解析库内部实现
- [ ] Python ruff、mypy、pytest 通过
