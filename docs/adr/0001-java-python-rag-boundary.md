# Java 负责业务中台，Python 负责 RAG 链路

## Status

accepted

## Decision

V1 沿用 RuoYi 的 Java/Vue 底座处理登录、角色、知识库、企业文档记录、会话、问答历史和反馈。新增 Python AI 服务，负责文档解析、切块、嵌入、检索和回答生成。

浏览器只访问 Java。问答采用 SSE 流式输出，由 Java 转发 Python 的流式事件；Java 与 Python 通过 Nacos 发现的内部 HTTP 接口协作。文档处理采用异步任务和结果回调。

原文件存入 MinIO，业务记录存入 MySQL。Milvus 使用单个 Collection 保存全部向量，通过 `knowledgeBaseId` 强制过滤形成知识库检索边界；文档和片段使用全局唯一 ID。

## Considered Options

- 将 RAG 编排放入 Java：会让模型、解析与检索的高频迭代进入业务中台，并弱化 Python 生态优势。
- 浏览器直连 Python：会绕过 RuoYi 的认证、业务记录与统一入口，并把内部 AI 服务暴露给客户端。
- 每个知识库使用独立 Milvus Collection：V1 的知识库只是检索边界，不是租户级物理隔离；大量 Collection 会增加索引和运维成本。

## Consequences

- Java-Python 契约必须覆盖异步入库、级联删除、SSE 事件、取消生成、幂等和失败恢复。
- 检索必须在真实 Milvus 集成测试中证明不会跨知识库返回片段。
- V1 不实现文档级或用户级检索权限；所有已登录用户都可选择全部可用知识库。
