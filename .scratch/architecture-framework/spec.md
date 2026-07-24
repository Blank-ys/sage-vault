# Sage Vault 架构与代码框架规格

Status: ready-for-agent

## Problem Statement

Sage Vault 已确定产品范围和 Java/Python 职责，但当前代码仍以 RuoYi 通用底座为主。后续实施工单缺少统一的模块所有权、依赖方向和代码准入规则，容易把知识库业务混入 `ruoyi-system`，让 Python 框架类型泄漏到跨进程契约，或在多个位置重复维护配置、测试和集成代码。

团队需要一套同时描述当前底座与 V1 目标结构的规格，作为 `docs/architecture.md` 和 `docs/code-framework.md` 的上游依据，使每张实施工单都能找到稳定的主模块、协作 seam 和验证 seam。

## Solution

在现有 RuoYi/Vue 系统旁建立清晰的 Sage Vault 业务模块：Java 知识库管理模块拥有业务状态，独立 Python RAG 服务拥有解析、切块、嵌入、检索、拒答和生成实现，前端按业务能力组织。浏览器仍只访问 Java；Java 与 Python 经 Nacos 发现的内部 HTTP/SSE interface 协作。

各模块采用小 interface、深实现和明确 adapter。跨模块只传项目自有契约，不传 LangChain、Milvus、DashScope 或其他供应商类型。配置、迁移、测试和运维资产随其 owner 就近存放；只有真正跨多个发布单元且已有稳定复用需求的内容才能提升为共享模块。

## User Stories

1. 作为实施开发者，我希望每张工单都有唯一主模块，以便知道业务规则应在哪里实现。
2. 作为实施开发者，我希望明确协作 seam 和验证 seam，以便跨模块改动不会演变为任意耦合。
3. 作为 Java 开发者，我希望知识库业务位于独立模块，以便不污染 RuoYi 的通用系统能力。
4. 作为 Java 开发者，我希望 Java 是知识库、企业文档、异步任务、会话、问答记录和反馈状态的唯一权威，以便重试和恢复只有一套真相。
5. 作为 Python 开发者，我希望 RAG 服务只接收幂等命令并返回执行结果，以便专注于高频演进的 AI 链路。
6. 作为 Python 开发者，我希望入库、回答和清理分别由深模块承接，以便复杂流水线不泄漏到 HTTP 路由。
7. 作为 Python 开发者，我希望 FastAPI 只处理 transport，以便业务行为能脱离 Web 框架测试。
8. 作为 Python 开发者，我希望 LangChain 只用于内部编排，以便升级或替换它时不改变跨进程契约。
9. 作为 Python 开发者，我希望每个 AI 服务独立构建和锁定依赖，以便未来服务可以独立演进。
10. 作为维护者，我希望只有两个真实服务出现稳定复用后才创建共享模块，以便避免假想抽象。
11. 作为维护者，我希望 AI 服务禁止源码互相导入，以便发布单元保持独立。
12. 作为维护者，我希望外部系统通过项目自有 port 和 adapter 接入，以便供应商 SDK 类型不扩散。
13. 作为维护者，我希望 Milvus adapter 强制接收知识库标识，以便任何检索路径都不能绕过知识库过滤。
14. 作为维护者，我希望百炼 adapter 输出项目自有流事件，以便供应商升级不改变问答语义。
15. 作为前端开发者，我希望问答、会话、知识库、企业文档和反馈按业务能力组织，以便逻辑不会散落到通用工具和全局状态中。
16. 作为测试开发者，我希望最高测试 seam 保持在 Java 对外 HTTP/SSE interface，以便一次验证完整用户行为和服务协作。
17. 作为测试开发者，我希望 Python application interface 可使用内存或假 adapter 测试，以便快速验证 RAG 行为而不依赖供应商。
18. 作为测试开发者，我希望用真实 Milvus 验证知识库隔离、发布和删除，以便保护最关键的数据不变式。
19. 作为测试开发者，我希望四类企业文档使用代表性解析夹具，以便验证项目行为而非解析库内部实现。
20. 作为测试开发者，我希望百炼始终由确定性假 adapter 替换，以便自动化测试无网络、费用和非确定性。
21. 作为运维人员，我希望依赖和模型版本可复现，以便部署环境不会在构建时意外漂移。
22. 作为运维人员，我希望模型硬件、Torch wheel 和并发参数形成显式运行 profile，以便资源不足能在发布前暴露。
23. 作为运维人员，我希望每个发布单元拥有自己的配置 schema、健康检查和镜像，以便独立部署和诊断。
24. 作为架构维护者，我希望架构文档只说明系统关系和依赖方向，以便它不会退化为易过时的目录清单。
25. 作为代码维护者，我希望代码框架文档说明目录准入和工单导航规则，以便新增工单可以自行判断落点。

## Implementation Decisions

- 遵循 ADR-0001：Java/Vue 负责业务中台，Python 负责 RAG 链路；浏览器只访问 Java，Java 与 Python 使用 Nacos 发现的内部 HTTP interface，Java 转发 SSE。
- 遵循 ADR-0002：本地 `bge-m3` 负责嵌入，Milvus 负责检索，百炼 `qwen-plus` 负责生成；生成供应商隐藏在可替换 adapter 后。
- Java 新业务归 `ruoyi-kb-management` 模块，不并入 `ruoyi-system`。具体包布局、平台依赖和 Java 测试 seam 仍由开放决策票确定。
- Python AI 代码归独立的 `ai-modules` 多服务容器；V1 只有 RAG 服务。每个 AI 服务是独立 Python 项目、发布单元、依赖图和镜像，禁止服务间源码导入。
- V1 不预建 `shared` 或 `common`。共享模块需同时满足：至少两个真实消费者、稳定需求、明确 owner、小 interface 和跨服务兼容测试。
- RAG 服务使用 Python `3.12.x`、FastAPI `0.139.x`、Uvicorn `0.51.x` 和 LangChain `1.3.x`。FastAPI 只负责 HTTP/SSE transport，LangChain 只存在于应用实现或专用 adapter。
- 版本范围的唯一规范如下；具体解析结果由 lockfile 固化，不在其他决策票中复制版本表：

| 能力 | 直接依赖基线 |
| --- | --- |
| 运行时 | Python `>=3.12,<3.13` |
| HTTP / SSE | FastAPI `>=0.139,<0.140`、Uvicorn `>=0.51,<0.52`；SSE 使用内置 `StreamingResponse` |
| 编排与配置 | LangChain `>=1.3.14,<2`、Pydantic `>=2.13,<3`、pydantic-settings `>=2.14,<3` |
| 存储客户端 | PyMilvus `>=2.4.10,<2.5`、MinIO `>=7.2,<8` |
| 模型客户端 | FlagEmbedding `>=1.4,<1.5`、DashScope `>=1.26.4,<2` |
| 文档解析 | pypdf `>=6.14,<7`、python-docx `>=1.2,<2`、markdown-it-py `>=4.2,<5`、charset-normalizer `>=3.4,<4` |
| 服务发现 | nacos-sdk-python `>=3.2,<4` |
| 测试 | pytest `>=9.1,<10`、pytest-asyncio `>=1.4,<2`、HTTPX `>=0.28,<0.29` |
| 工程工具 | uv `0.11.32`、Ruff `0.16.0`、mypy `2.3.0` |

- RAG 应用分为入库、回答和清理三个深模块；transport 只做协议转换，执行模型不复制 Java 业务状态机。
- MinIO、Milvus、`bge-m3`、百炼、Java 回调和 Nacos 是真实 seam，均使用项目自有 port、生产 adapter 和测试替身。格式解析与 LangChain 内部步骤默认保留为内部 seam。
- Python 使用进程内有界执行器处理长任务。Java 持久化任务状态并负责超时、重试和崩溃后重发；V1 不引入 Celery、Python 业务数据库或第二套持久化任务状态。
- RAG 容器默认单 worker，避免重复加载 `bge-m3`。异步 I/O、有界执行器和 semaphore 控制并发；横向扩容前必须先决定取消路由和模型内存预算。
- 直接依赖使用下界和下一不兼容版本上界；uv lockfile 精确固定完整依赖图。生产与 CI 使用 frozen lock，禁止部署时重新求解。
- Milvus 2.4.23 只配 PyMilvus `2.4.x`，禁止使用 PyMilvus 3.0。
- Python 补丁版本、uv/Ruff/mypy、Torch wheel 来源、模型 revision 和运行镜像精确锁定。Starlette、AnyIO、Torch、Transformers 等传递依赖只由 lockfile 固化，除非代码直接导入或需规避已证实缺陷。
- 前端业务切片、Java-Python 契约所有权、横切资产归属和 12 张实施工单导航仍由路线图中的开放决策票确定；规格不提前替这些票作答。
- 最终 `architecture.md` 只描述当前/目标系统、运行时关系、模块职责、数据所有权和依赖方向；`code-framework.md` 只描述目录/包准入、interface/adapter 规则、测试资产位置和工单导航，避免互相复制。

## Testing Decisions

- 最高且主要的 seam 是 Java 对浏览器暴露的 HTTP/SSE interface。系统验收同时运行 Java 和 Python，使用真实 MySQL、MinIO、Milvus，并注入确定性假生成 adapter。
- Python 单元测试只经过入库、回答和清理的 application interface，观察命令结果、流事件和外部调用，不断言 LangChain chain、内部步骤或 SDK 对象。
- HTTP/SSE 契约测试覆盖校验、错误映射、事件顺序、断连、取消、慢消费者和 Java 转发。
- 真实 Milvus 2.4.23 集成测试覆盖 `knowledgeBaseId` 强制过滤、整篇发布、即时检索排除和幂等删除。
- 解析器集成测试使用 PDF、DOCX、Markdown 和 TXT 的成功与失败夹具，断言文本、页码和来源元数据，不测试第三方库实现。
- `bge-m3` 镜像 smoke test 覆盖模型加载、固定维度、批处理、中文样本和资源峰值；具体 CPU/GPU profile 由开放决策票补齐。
- 自动化测试不访问百炼；真实 DashScope 仅在部署后人工冒烟。
- 依赖升级必须重新生成 lockfile，并通过静态检查、上述契约/集成测试及目标模型镜像 smoke test；resolver 成功本身不构成兼容证明。

## Out of Scope

- 实现知识问答 V1 的业务功能或改变既有产品验收标准。
- 推翻 Java/Python 职责、百炼选择或数据出网决策。
- 为尚不存在的 AI 服务预先设计业务 interface、数据模型或共享库。
- 引入 Celery、Python 业务数据库、OCR、重排、多模型后台配置或生产级高可用架构。
- 重构与 Sage Vault 无关的 RuoYi 通用模块。

## Further Notes

- 产品行为与质量指标由[通用企业文档问答 V1 规格](../knowledge-qa/spec.md)管理，本规格不复述其 89 条原始用户场景。
- Python 版本兼容事实和一手来源保留在[调研 Python 框架与版本兼容基线](issues/07-research-python-framework-version-baseline.md)；精确安装结果最终以提交的 lockfile 为准。
- 路线图尚未完成。Java、前端、跨进程契约、模型运行 profile、横切资产和工单导航决策解决后，本规格需要同步收敛，再生成仓库正式架构文档。
