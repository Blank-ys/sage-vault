# 调研 Python 框架与版本兼容基线

Type: research
Status: resolved
Blocked by: none

## Question

截至 2026-07-24，哪些受官方支持且彼此兼容的 Python、Web/RAG 框架、外部客户端和工程工具适合作为 RAG 服务基线？

## Answer

### 兼容结论

- Python 3.12 仍受官方支持，且比 3.13/3.14 更稳妥覆盖 `bge-m3` 的 Torch/Transformers 原生依赖。
- FastAPI 0.139.x、LangChain 1.3.x、Pydantic 2.13.x 和 pydantic-settings 2.14.x 的声明范围相交；SSE 可直接使用 `StreamingResponse`。
- Milvus 2.4.23 必须配 PyMilvus 2.4.x，建议 `>=2.4.10,<2.5`；不能使用 PyMilvus 3.0。
- FlagEmbedding 1.4.x、DashScope 1.26.x、Nacos SDK 3.2.x 及所选解析库支持 Python 3.12，但完整组合仍需 lockfile 和目标镜像验证。
- 应用库声明兼容范围，uv lockfile 固化全部传递版本；Python 镜像补丁、uv/Ruff/mypy、Torch wheel 来源和模型 revision 精确锁定。

### 未由元数据关闭的风险

- FlagEmbedding 的 Torch/CUDA 组合、批处理和资源峰值必须在目标镜像中 smoke test。
- FastAPI 对 Starlette 无上界，实际 lock 版本必须通过 SSE 断连、取消和 Java 转发测试。
- 文档解析库不保证项目的 50 MB、页码保留、失败诊断和性能目标，需用代表性夹具验证。
- LangChain 升级频繁，只能替换内部编排实现，不能改变业务状态、知识库过滤或外部契约。

### 一手来源

访问日期：2026-07-24。

- [Python 版本状态](https://devguide.python.org/versions/)
- [FastAPI 发布记录](https://fastapi.tiangolo.com/release-notes/)与 [StreamingResponse](https://fastapi.tiangolo.com/advanced/custom-response/#streamingresponse)
- [LangChain 发布元数据](https://pypi.org/pypi/langchain/json)
- [uv lock 与同步](https://docs.astral.sh/uv/concepts/projects/sync/)
- [PyMilvus 兼容矩阵](https://raw.githubusercontent.com/milvus-io/pymilvus/master/README.md)与 [2.4 分支建议](https://raw.githubusercontent.com/milvus-io/pymilvus/2.4/README.md)
- [BGE-M3](https://bge-model.com/bge/bge_m3.html)与 [FlagEmbedding 1.4.0 元数据](https://pypi.org/pypi/FlagEmbedding/1.4.0/json)
- [百炼 SDK 安装](https://help.aliyun.com/zh/model-studio/install-sdk)与 [Qwen interface](https://help.aliyun.com/zh/model-studio/qwen-api-reference/)

采用的版本策略和测试门禁见 [Sage Vault 架构与代码框架规格](../spec.md)。
