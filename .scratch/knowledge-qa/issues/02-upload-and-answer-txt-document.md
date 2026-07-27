# 02 — 上传并问答一篇 TXT 企业文档

**What to build:** 知识管理员能够向知识库上传一篇 TXT 企业文档，系统在后台完成存储、切块、嵌入和向量发布；文档可用后，普通用户能够在绑定该知识库的会话中获得基于文档内容的流式中文回答或可靠拒答。

**Blocked by:** 01 — 打通空知识库问答细线.

**Status:** split

- [ ] 上传成功后原文件进入 MinIO、业务记录进入 MySQL，接口立即返回且文档显示为处理中。
- [ ] Python 异步读取 TXT，按自然段和可配置长度/重叠切块，使用本地 `bge-m3` 嵌入并将带 `knowledgeBaseId`、文档 ID、片段 ID、文件名和顺序的向量写入单个 Milvus Collection。
- [ ] 只有全部处理成功后文档才显示为可用并参与检索；问答只召回当前会话知识库内的可用片段。
- [ ] 假模型基于召回片段产生 `started`、`delta`、`completed` 流；证据低于可配置拒答阈值时返回 `refused`，不得用模型自身知识补答。
- [ ] 系统验收测试从上传一直覆盖到可用文档的流式回答，并用真实 Milvus 证明语义相近的其他知识库片段不会被召回。

## Comments

### 2026-07-27 Split into tracer-bullet tickets

This ticket has been split into the following sub-tickets to keep each implementation window focused:

- [02a — Java 企业文档上传、MinIO 原文件与业务记录](02a-java-document-upload-and-minio-storage.md)
- [02b — Python RAG TXT 入库：解析、切块、嵌入、Milvus 写入](02b-python-txt-parse-chunk-embed-milvus.md)
- [02c — 跨端异步入库契约与状态机](02c-async-indexing-contract-and-state-machine.md)
- [02d — 基于 Milvus 召回的流式问答与拒答](02d-retrieval-based-streaming-answer.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.

