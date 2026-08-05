# 02d — 基于 Milvus 召回的流式问答与拒答

**What to build:** 当知识库中存在可用 TXT 文档时，普通用户提问后系统召回当前知识库内的可用片段，由假生成模型产生 `started`/`delta`/`completed` 流；证据低于可配置阈值时返回 `refused`，且不得用模型自身知识补答。

**Blocked by:** 02b — Python RAG TXT 入库；02c — 跨端异步入库契约与状态机.

**Status:** resolved

- [x] Python 回答链路接入检索模块，按 `knowledgeBaseId` 强制标量过滤
- [x] 实现确定性假生成 adapter，基于召回片段产生 `started`/`delta`/`completed`/`refused` 事件
- [x] 配置拒答阈值，证据不足时返回 `refused`，不得用模型自身知识补答
- [x] Java 转发 SSE 事件并持久化问答记录终态
- [x] 系统验收测试覆盖：上传 TXT → 文档变为可用 → 提问 → 收到流式中文回答
- [x] 真实 Milvus 隔离测试：语义相近的其他知识库片段不会被召回
