---
paths:
  - "ai-modules/**/*.py"
  - "ai-modules/**/pyproject.toml"
  - "ai-modules/**/uv.lock"
  - "ai-modules/**/Dockerfile"
  - "ai-modules/**/config/**/*"
---

# Python AI 模块规则

处理 `ai-modules/` 前先读根 `AGENTS.md`、`docs/architecture.md` 和 `docs/code-framework.md`。V1 只有独立发布的 RAG 服务；目录尚未落地时不得预建共享包或虚构验证命令。

## 模块与依赖

- RAG 的入库、回答和清理是三个深 application 模块；FastAPI 只承担 HTTP/SSE transport。
- application 只依赖项目自有 model 和 ports。MinIO、Milvus、`bge-m3`、DashScope、Java callback 与 Nacos 都通过 adapter 接入。
- Pydantic transport 类型停留在 `transport/http`；第三方 SDK、LangChain、Torch 和供应商类型不得进入 port、执行模型或 wire contract。
- LangChain 只用于 application 内部编排或专用 adapter；不要因测试方便公开 chain、解析步骤或第三方对象。
- 每个 AI 服务独立构建、锁依赖和部署，禁止服务间源码导入；至少两个真实消费者形成稳定需求前不创建 Python `shared` 或 `common`。

## RAG 与向量

- 使用 `bge-m3` 生成 1024 维、L2 归一化的稠密向量；所有检索必须强制按 `knowledgeBaseId` 过滤。
- 距离类型仍待评测裁定，不得自行把候选写入契约或基线。
- 入库只有在解析、切块、嵌入和 Milvus 原子发布全部成功后才回报成功；失败或删除中的文档不得可检索。
- 清理必须幂等，并能处理重复命令、旧 `attempt` 和部分失败；Python 返回执行结果与安全诊断，不裁决 Java 业务终态。
- DashScope adapter 把供应商流转换为项目自有事件；默认模型标识是 `qwen-plus`，SDK 类型和凭据不得离开 adapter。

## 模型与运行 profile

- 模型必须由 40 位 Hugging Face commit SHA、逐文件 SHA-256 和 MinIO 不可变版本标识；`main`、tag 或模型名不算版本。
- 运行时只从校验通过的显式本地目录加载，禁止联网下载或静默切换模型。
- Windows GPU profile 使用 FP16、单进程、单 worker、batch 4、嵌入 semaphore 1；CUDA 不可用或推理异常时撤销 readiness，不得静默降级 CPU。
- `cpu-dev` 使用 FP32、batch 1、semaphore 1，只用于排障。Linux CPU profile 证明可移植性，不代表生产性能。
- 查询和入库共享一个 GPU 执行槽；查询队列优先且上限 5，入库队列上限 1 个批次，溢出返回已注册的可重试忙碌错误。

## Transport、状态与安全

- Python 不复制知识库、文档、任务、会话、问答或反馈状态机，也不直连 Java 业务表。
- 异步命令快速返回 `202 Accepted`，以 `taskId`/`attempt` 幂等；回调携带执行结果，由 Java 裁决重复、乱序、重试和最终状态。
- SSE 只发送根契约注册的项目事件。取消必须关联 `generationId` 并作用于原执行实例；连接关闭本身不是业务取消。
- 内部调用校验部署签名、时间戳和重放窗口，传播 `requestId` 以及任务或生成 ID；不接受转发的用户 token 作为内部认证。
- 日志不得包含问题、回答、文档片段、完整 prompt、预签名 URL、模型输入或凭据；诊断只返回注册的安全元数据。

## 健康检查

- liveness 只检查进程和事件循环。
- readiness 在启动或模型重载后校验 revision/哈希、目标设备、模型加载和固定中文嵌入，并缓存结果；日常探针只读取状态。
- CUDA OOM、设备丢失、推理异常或制品校验失败必须立即撤销 readiness。

## 验证

- 依赖清单落地后运行 `uv run ruff check .`、`uv run mypy .` 和 `uv run pytest`；精确入口以服务 `pyproject.toml` 为准。
- 解析格式使用代表性 fixture 做 parser 集成测试；向量隔离、发布和清理通过项目 port 使用真实 Milvus 验证。
- 运行 Windows GPU 与 Linux CPU smoke，分别核对制品、设备、精度和 readiness；真实百炼只做部署后人工 smoke。
- 契约改动运行根 schema/样例检查和 Python consumer/provider 测试；完整用户行为仍由浏览器到 Java 的系统验收证明。
