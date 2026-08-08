# Sage Vault 系统架构

本文是 Sage Vault 系统关系的权威文档，描述当前底座、V1 目标模块、运行时协作、数据所有权、依赖方向与关键 seam。完整代码目录、目录准入规则和实施工单导航见 [代码框架](code-framework.md)。产品行为和验收指标仍以 [通用企业文档问答 V1 规格](../.scratch/V1.0(knowledge-qa)/spec.md) 为准。

## 架构原则

- Java/Vue 拥有业务中台，Python 拥有 RAG 执行链路；浏览器只访问 Java。
- 权威业务状态只有一份。知识库、企业文档、异步任务、会话、问答记录和反馈由 Java 管理，Python 不复制业务状态机。
- 模块提供小 interface 和深实现。跨模块只经过公开 interface，框架、数据库和供应商类型不得跨越 seam。
- 资产随 owner 就近放置。只有确实跨多个发布单元、owner 明确且只通过公开 interface 工作的资产才能进入仓库级目录。
- 测试优先经过最高可观察 seam。模块测试用于快速定位，不能替代完整用户承诺的系统验收。

这些原则落实 [ADR-0001：Java/Python RAG 职责](adr/0001-java-python-rag-boundary.md) 与 [ADR-0002：百炼生成与数据出网](adr/0002-bailian-generation-and-egress-boundary.md)。

## 当前底座

仓库当前以 RuoYi Cloud 3.6.8 和 Vue 3 为底座：

- `ruoyi-gateway` 是浏览器统一入口，`ruoyi-auth` 负责登录认证。
- `ruoyi-system` 拥有用户、角色、菜单和权限标识。
- Java 服务通过 Nacos 获取配置和服务发现，MySQL 与 Redis 提供底座存储。
- 前端复用 RuoYi 的动态菜单、路由、布局、认证拦截、全局通知和基础 UI。
- MinIO、Milvus、etcd 与 Attu 已有独立开发编排，但尚未与 RuoYi 基础组件形成项目级唯一入口。
- Sage Vault 专用 Java 发布单元（`backend/ruoyi-kb-management`）、Python RAG 服务（`ai-modules/services/rag`）、根级跨端契约（`contracts/`）和浏览器到 Java 的系统验收（`system-tests/`）已落地；评测资产（`evaluation/`）与 V1 项目级唯一编排（`deploy/`）仍属 V1 目标结构。

现有 RuoYi 模块保持原状；V1 不为统一目录外观而重构与 Sage Vault 无关的底座代码。

## 技术栈与版本

完整技术栈、版本状态、运行 profile 和升级规则统一由 [技术栈与版本](technology-stack.md) 维护。根 [AGENTS.md](../AGENTS.md#技术栈) 只保留高频摘要；本文只说明技术选择在系统中的职责和运行关系。

## V1 目标系统

```text
Browser
   |
   | HTTP / SSE
   v
RuoYi Gateway ---- RuoYi Auth / System
   |
   v
Knowledge Base Management (Java)
   |  \-- MySQL: authoritative business state
   |  \-- MinIO: enterprise document originals
   |  \-- RuoYi audit adapter
   |
   | signed internal HTTP / SSE, discovered through Nacos
   v
RAG (Python)
   |  \-- bge-m3: local embeddings
   |  \-- Milvus: vectors and retrieval
   |  \-- Bailian qwen-plus: answer generation
   |  \-- Java callback adapter
   |
   +-- Nacos configuration and discovery
```

### Java 业务发布单元

知识库管理是由后端父工程直接聚合的单一 Maven/Spring Boot 发布单元，与现有 RuoYi 模块容器同级，不并入 `ruoyi-system`，也不拆成多个微服务。

发布单元内部按五个业务能力组织：知识库、企业文档、会话、问答记录和反馈。异步任务随发起它的能力就近组织。每个能力的 Controller 只调用本能力 application interface；跨能力协作也只能调用对方公开 application interface，不得直接访问 Mapper、持久化对象、Service 实现或 adapter。

会话能力拥有创建会话与发起问题的用例编排，通过两个公开 application interface 暴露给 Controller：`ConversationService` 承载会话 CRUD 与历史投影，`AnswerSessionService` 承载回答生命周期。`AnswerSessionServiceImpl` 收敛发起事务、SSE 事件流、停止信号、RAG 取消与终态裁决；它直接通过本能力 Mapper 校验会话归属，通过 `KnowledgeBaseService` 校验知识库可用性，通过 `QaRecordService` 创建和裁决问答记录，并通过 RAG port 发起和取消回答；不得通过 Service 自调用来访问本能力数据。

发起问题的流式方法不持有覆盖 RAG HTTP/SSE 生命周期的数据库事务。问答记录创建和每次状态裁决由 `QaRecordService` 的独立短事务完成；事务提交后才调用外部 RAG，流错误通过新的短事务裁决为未完成，连接断开仍不等同于用户主动取消。

Issue 01 中 `STARTED` 表示 Java 已接受本次生成；Python 的 `started` SSE 事件只向浏览器确认执行链路开始，不重复更新数据库，也不为此新增 `PENDING` 或 `ACCEPTED`。最小问答记录迁移是 `STARTED` 到 `REFUSED` 或 `UNFINISHED`。同一终态的重复事件幂等成功，迟到事件不得覆盖已有终态；`QaRecordService` 裁决零行更新是幂等、终态冲突还是记录缺失，Mapper 只执行 Service 指定的条件 SQL。

Java 是以下事实的唯一权威：

- 知识库及其可用、删除中、删除失败等状态。
- 企业文档身份、文件名占用、处理状态、重试与删除状态。
- 入库和清理任务、尝试次数、重试资格、租约和恢复裁决。
- 会话绑定、问答记录、单个用户的活跃回答和最终状态。
- 反馈授权、处理状态和可见正文。

知识库名称唯一性由 Service 预检和 MySQL `normalized_name` 唯一约束共同保证：预检提供正常路径的明确反馈，数据库约束裁决并发竞争。名称规范化由 Java 业务类型完成，Mapper 只持久化结果；Service 将唯一键冲突统一映射为知识库名称冲突业务异常。

普通用户的可用知识库列表由 Service 指定 `AVAILABLE`，Mapper 在 MySQL 中过滤；知识管理员完整列表与普通用户可用列表都按 `updated_at DESC, id DESC` 稳定排序，但使用不同查询。创建、修改内容或变更状态都会推进知识库更新时间；时间字段属于持久化排序事实，Issue 01 不因此扩展公开 Response。

企业文档原文件通过模块内 MinIO adapter 管理，不经过 `ruoyi-file`。业务表只引用稳定的 RuoYi 用户 ID，不复制用户、角色或部门信息，也不建立跨服务数据库外键。

### Python RAG 发布单元

AI 根目录允许容纳多个独立发布单元，但 V1 只有 RAG。每个 AI 发布单元独立构建、锁依赖和部署，禁止服务间源码导入；在至少两个真实消费者形成稳定需求前，不创建共享 Python 包。

RAG 内部有三个深模块：

- **入库**：读取企业文档、解析、切块、嵌入并原子发布向量。
- **回答**：查询嵌入、知识库内检索、拒答判断和流式生成。
- **清理**：幂等清理解析产物与 Milvus 向量。

FastAPI 只承担 HTTP/SSE transport，RAG 编排由项目自有 application services 手写实现，不使用 LangChain。MinIO、Milvus、`bge-m3`、百炼、Java 回调与 Nacos 都通过项目自有 port 接入；第三方 SDK 类型不得进入 application interface 或跨端契约。

### 前端业务切片

前端以四个 feature 承载 Sage Vault：

- `conversations`：问答工作台、会话历史、问答记录和流生命周期。
- `knowledge-bases`：普通用户选择知识库和知识管理员管理知识库。
- `enterprise-documents`：列表、批量上传、状态、重试和删除。
- `feedback`：普通用户提交反馈和知识管理员处理反馈。

页面、局部状态、Java adapter、DTO 映射和专用 UI 随 feature 就近存放。跨 feature 只使用公开入口；Sage Vault 业务状态不得进入 RuoYi 全局 store。后端状态始终权威，前端不根据请求过程推导业务终态。

浏览器只调用 Java。问答使用 `fetch` 读取 Java SSE，以携带认证头并使用 `AbortController` 管理连接；用户停止生成还必须调用显式取消 interface，不能把连接断开当作业务取消。

## 运行时流程

### 企业文档入库

1. 知识管理员经 Java 上传企业文档。
2. Java 在同一 MySQL 本地事务中写入企业文档状态和持久化任务，提交后才派发。
3. Java 通过带对象版本或校验和的限时预签名 URL 向 Python 提供原文件。
4. Python 快速返回 `202 Accepted`，异步完成解析、切块、嵌入和 Milvus 发布。
5. Python 通过签名回调返回任务身份、尝试、结果和安全诊断；Java 裁决重复、旧尝试与乱序回调。
6. 只有完整成功后 Java 才把企业文档标记为可用；失败或删除中的企业文档始终不可检索。

异步命令采用至少一次投递并以 `taskId`/`attempt` 幂等收敛。网络超时重投沿用当前尝试，只有知识管理员显式重试才增加 `attempt`。

### 流式问答与取消

1. Java 为问答创建 `generationId`，通过 Nacos 选择 RAG 实例并保持实例亲和。
2. Python 只检索会话绑定知识库中的可用片段，并返回项目自有 SSE 事件。
3. Java 校验、转发事件并增量持久化内容，最终裁决完整、拒答、已停止或未完成。
4. 显式取消必须发回原 RAG 实例；实例不可达时 Java 关闭浏览器流、保存已有内容为已停止，并记录取消未确认。
5. 意外断流保存为未完成，不得与用户停止混同。

V1 不建立跨实例活跃生成注册表。未来横向扩容若无法保证实例亲和，再单独引入取消协调机制。

### 删除与恢复

企业文档删除先在 Java 进入删除中并立即退出新检索，再异步清理 MinIO、解析产物和 Milvus 向量；清理成功后才释放文件名。知识库删除由知识库能力协调所有企业文档清理，并在清理窗口拒绝上传、创建会话和提问。

Java 模块内 scheduler 扫描待发送、超时和可重试任务，并通过数据库抢占、租约或乐观锁支持多实例恢复。V1 不引入 XXL-JOB；未来调度器只能成为恢复触发 interface 的 adapter，不能拥有任务状态机。

## Java-Python 契约

根级版本化契约是双向 HTTP、回调和 SSE wire contract 的唯一权威，包含 OpenAPI、SSE JSON Schema、错误注册表和共同样例。Java 与 Python 在各自 transport adapter 内手写模型并映射到本端 application interface；V1 不生成代码。

- Java 拥有业务身份、任务/尝试、状态裁决、重试和取消语义。
- Python 拥有 RAG 执行结果、流事件能力和安全诊断元数据。
- 内部调用使用部署密钥签名、时间戳和重放窗口，不转发用户 token 或角色。
- 所有调用传播 `requestId`，并按链路携带 `taskId`/`attempt` 或 `generationId`。
- HTTP 表达请求层结果；业务错误使用注册的六位整数码。Java 独自把错误映射为业务状态、重试和用户文案。
- `v1` 只允许兼容性新增；破坏性变更必须进入新契约版本。

Nacos 只负责发现和配置，不构成认证机制。

## 数据与安全所有权

| 事实或资源 | Owner | 禁止事项 |
| --- | --- | --- |
| 用户、角色、菜单、权限标识 | RuoYi System | Sage Vault 不复制管理员名单或账号资料 |
| 知识库、企业文档记录、任务 | Java 知识库管理 | Python 和其他 RuoYi 服务不得直连业务表 |
| 会话、问答记录、反馈 | Java 知识库管理 | 前端 store 和 Python 不建立第二套权威状态 |
| 企业文档原文件 | Java 企业文档能力及 MinIO adapter | `ruoyi-file` 不参与业务流程；Python 无管理权限 |
| 解析产物与向量 | Python RAG 及 Milvus adapter | Java 不直连 Milvus 实现业务流程 |
| 跨端 wire contract | Java/Python 共同维护 | 两端不得复制或临时扩展未注册语义 |
| 评测集与正式报告 | 试点负责人 | 真实敏感材料和大体积产物不得提交 Git |

知识管理员管理操作只通过 `ManagementAudit` interface 向 RuoYi 审计能力发送白名单字段。问题、回答、企业文档片段、完整提示词和凭据不得进入操作审计或技术日志。提交反馈是知识管理员查看对应问答正文的授权 seam；未提交的问答正文仍不可见。

## 部署与模型 profile

首个试点中，前端、Java 与 Python 在 Windows 宿主机原生运行；Ubuntu 24 VMware 虚拟机只运行 MySQL、Redis、Nacos、MinIO、Milvus 等中间件。基础组件由项目级唯一开发编排启动。

本地嵌入目标 profile 使用 RTX 4060 Laptop GPU（8 GB 显存）：Python 3.12、FlagEmbedding、FP16、单进程、单 worker、单模型实例、batch 4（经 `.env` 配置）、嵌入 semaphore 1。代码不按 profile 自动切换 batch，由 `embedding_batch_size` 显式配置；共享执行槽仍为 V1 目标。CUDA 不可用时 GPU profile 保持 not ready，禁止静默降级。显式 `cpu-dev` 使用 FP32、batch 1、semaphore 1，只用于排障；Linux CPU 镜像只证明可移植性。

问答查询与企业文档入库共享一个 GPU 执行槽是 V1 目标，尚未实现：当前 indexing 与 answering 各创建独立 `BgeM3Embedder` 实例，未共享执行槽。查询队列优先且上限为 5、入库队列上限为 1 个批次、溢出返回可重试忙碌错误同为 V1 目标；代码仅预留 `embedding_max_queue_size` 设置且默认禁用，`errors.yaml` 也未注册忙碌错误码。五路并发回答仍由完整系统验收证明，不等同于五路并行嵌入。

程序与模型分开发布。模型以 Hugging Face 40 位 commit SHA、逐文件 SHA-256 清单和 MinIO 不可变版本共同标识；运行时只从校验通过的显式本地目录加载并禁止联网。Windows CUDA 与 Linux CPU 依赖使用各自 PyTorch 官方索引和冻结离线 wheelhouse。

模型实际 revision、Torch 最终精确版本、两平台 lock 和 wheel 哈希仍需在正式发布前完成目标环境验证；候选版本不得写成已确认基线。

## 可用性与验证 seam

liveness 只检查进程和事件循环。完整 readiness 在启动或模型重载后校验模型 revision/哈希、目标设备、模型加载和固定中文嵌入并缓存结果；日常检查只读取状态。CUDA OOM、设备丢失或推理异常立即撤销 readiness。

最高且主要的验证 seam 是浏览器经 Gateway 到 Java 的真实 HTTP/SSE interface。Java 分别访问权威业务状态 MySQL 与 Python RAG；Python 不访问 MySQL，也不得直连 Sage Vault 业务表。完整系统验收同时运行 Java 与 Python，使用真实 MySQL、MinIO 和 Milvus，并在自动化中注入确定性假生成 adapter。必要补充包括：

- Java application、MySQL、MinIO、恢复、权限与审计测试。
- Java-Python schema、样例和双向 consumer/provider 契约测试。
- Python 入库、回答、清理的 application 测试，以及真实 Milvus 和解析器集成测试。
- Windows GPU、Linux CPU 模型 smoke，以及部署后真实百炼人工 smoke。
- 两端日志单测和通过完整链路的正文泄漏探针。

测试断言公开行为和状态，不断言 Controller、Mapper、组件私有方法或 adapter 内部调用顺序。

## 文档所有权

- 本文只拥有系统关系、运行时、数据所有权、依赖方向和关键 seam。
- [代码框架](code-framework.md) 唯一拥有完整目标目录树、目录准入、测试/配置/部署落点和实施工单导航。
- [技术栈与版本](technology-stack.md) 唯一拥有按功能分类的完整技术栈、版本状态、运行 profile 和升级规则。
- 根 [AGENTS.md](../AGENTS.md#技术栈) 只拥有供 Agent 快速定位的技术栈摘要。
- [领域词汇](../CONTEXT.md) 只拥有业务术语，不记录实现决策。
- [ADR](adr/) 记录难以逆转且需要保留取舍背景的系统决策。

架构事实变化时，先更新拥有该事实的文档，再检查其他文档的链接和简短摘要；禁止复制整段内容形成第二份真相。
