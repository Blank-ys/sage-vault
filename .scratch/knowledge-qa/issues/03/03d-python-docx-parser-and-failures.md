# 03d — Python DOCX 解析与可理解失败

**What to build:** DOCX 文件可被解析、切块、入库和问答；损坏或空白 DOCX 进入 `FAILED` 状态并展示具体原因。

**Blocked by:** 03b — 需要统一的解析器接口。

**Status:** ready-for-agent

- [ ] 实现 `DocxParser`，保留段落与标题层级
- [ ] 损坏 DOCX 返回可理解的失败原因
- [ ] 空白 DOCX 返回“文档内容为空”
- [ ] 解析失败时不向 Milvus 写入任何片段，回调 Java 的 `FAILED` 状态
- [ ] 集成测试使用成功与失败 DOCX fixture，只断言可观察文本和来源元数据
- [ ] Python ruff、mypy、pytest 通过
