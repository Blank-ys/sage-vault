# 02b — Python RAG TXT 入库：解析、切块、嵌入、Milvus 写入

**What to build:** 在 Python RAG 服务内新增入库流水线，异步读取 TXT，按自然段和可配置长度/重叠切块，使用本地 `bge-m3` 嵌入，并将带 `knowledgeBaseId`、文档 ID、片段 ID、文件名和顺序的向量写入单个 Milvus Collection。

**Blocked by:** 01 — 打通空知识库问答细线；02a 完成后需与 02c 对齐契约.

**Status:** ready-for-agent

- [ ] 新增 `application/indexing` 深模块；transport 只做协议转换
- [ ] 实现 TXT parser adapter
- [ ] 按自然段和可配置长度/重叠参数切块
- [ ] 实现本地 `bge-m3` 嵌入 adapter，支持 GPU/CPU profile
- [ ] 定义 Milvus collection schema，写入带 `knowledgeBaseId`、`documentId`、`chunkId`、`filename`、`sequence`、`text` 的向量
- [ ] 入库失败时清理本次尝试写入的 Milvus 向量和解析产物
- [ ] Python 应用测试、真实 Milvus 集成测试通过
