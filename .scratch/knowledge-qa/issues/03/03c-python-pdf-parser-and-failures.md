# 03c — Python PDF 解析与可理解失败

**What to build:** 带文本层的 PDF 可被解析、切块、入库和问答；加密、损坏、空白、扫描版 PDF 或无法提取文本的 PDF 进入 `FAILED` 状态，并向管理员展示具体原因。

**Blocked by:** 03b — 需要统一的解析器接口。

**Status:** ready-for-agent

- [ ] 实现 `PdfParser`，提取文本并记录页码
- [ ] 加密 PDF 返回“文件已加密，无法解析”
- [ ] 损坏 PDF 返回“文件损坏，无法解析”
- [ ] 空白或扫描版 PDF 返回“未检测到可提取文本”
- [ ] 解析失败时不向 Milvus 写入任何片段，回调 Java 的 `FAILED` 状态
- [ ] 集成测试使用成功与失败 PDF fixture，只断言可观察文本和来源元数据
- [ ] Python ruff、mypy、pytest 通过
