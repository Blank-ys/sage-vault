# 确定 ai-modules 多服务框架与 RAG 服务布局

Type: grilling
Status: resolved
Blocked by: 07

## Question

根级 `ai-modules/` 如何容纳可独立演进的 AI 服务，首个 RAG 服务采用什么技术基线和内部模块，使框架与供应商实现不泄漏到跨进程 interface？

## Answer

采用 Python 3.12、FastAPI，具体版本以架构规格的唯一版本表为准。FastAPI 只负责 HTTP/SSE transport，RAG 编排由项目自有 application services 实现，不引入 LangChain；Java 保持业务状态和异步任务的唯一权威，Python 不引入 Celery 或业务数据库。

每个 AI 服务独立构建、锁依赖和部署，服务间禁止源码导入。V1 不预建共享包；只有至少两个真实服务产生稳定复用需求后才提取共享模块。

```text
ai-modules/
└── services/
    └── rag/
        ├── pyproject.toml
        ├── uv.lock
        ├── Dockerfile
        ├── src/sage_vault_rag/
        │   ├── bootstrap/
        │   ├── transport/http/
        │   ├── application/{indexing,answering,cleanup}/
        │   ├── model/
        │   ├── ports/
        │   └── adapters/{minio,milvus,bge_m3,dashscope,java_callback,nacos}/
        └── tests/{unit,contract,integration,smoke}/
```

入库、回答和清理是三个深模块；transport 只转换协议。供应商 SDK 类型不得进入 HTTP schema、执行模型、port 或 Java-Python 契约。Milvus adapter 强制知识库过滤，百炼 adapter 转换为项目自有流事件。容器默认单 worker，通过有界执行器和 semaphore 控制并发。

完整实施与测试约束见 [Sage Vault 架构与代码框架规格](../spec.md)，版本依据与风险见 [调研 Python 框架与版本兼容基线](07-research-python-framework-version-baseline.md)。
