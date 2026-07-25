# Sage Vault 架构与代码框架规格

Status: ready-for-agent

## Problem Statement

Sage Vault 已确定产品范围和 Java/Python 职责，但当前代码仍以 RuoYi 通用底座为主。后续实施工单缺少统一的模块所有权、依赖方向和代码准入规则，容易把知识库业务混入 `ruoyi-system`，让 Python 框架类型泄漏到跨进程契约，或在多个位置重复维护配置、测试和集成代码。

团队需要一套同时描述当前底座与 V1 目标结构的规格，作为 `docs/architecture.md` 和 `docs/code-framework.md` 的上游依据，使每张实施工单都能找到稳定的主模块、协作 seam 和验证 seam。

## Solution

在现有 RuoYi/Vue 系统旁建立清晰的 Sage Vault 业务模块：`ruoyi-kb-management` 作为与 RuoYi 模块容器同级的单一 Java 发布单元，拥有知识库、企业文档、异步任务、会话、问答记录和反馈的业务状态；独立 Python RAG 服务拥有解析、切块、嵌入、检索、拒答和生成实现；前端按业务能力组织。浏览器仍只访问 Java；Java 与 Python 经 Nacos 发现的内部 HTTP/SSE interface 协作。

Java 发布单元内部按知识库、企业文档、会话、问答记录和反馈聚合代码，每个业务能力内部保留 RuoYi 开发者熟悉的技术角色。前端以 `conversations`、`knowledge-bases`、`enterprise-documents` 和 `feedback` 四个 feature 就近组织页面、状态、Java adapters 与专用 UI。各模块采用小 interface、深实现和明确 adapter；跨业务能力只经过公开 interface，不直接访问对方实现。跨发布单元只传项目自有契约，不传 LangChain、Milvus、DashScope 或其他供应商类型。

配置、SQL、测试和运维资产默认随 owner 就近存放；只有跨多个发布单元、owner 明确且只通过公开 interface 工作的资产才能进入仓库级目录。正式架构文档分别拥有系统关系和完整代码结构，根 Agent 规则强制新会话先读取这两份文档，再按改动范围读取各端规则。每张实施工单以权威状态或关键不变量确定唯一主落点，并独立列出协作与验证落点。

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
26. 作为 Java 开发者，我希望 Sage Vault 的 Java 业务集中在一个独立发布单元，以便保持本地事务并避免过早引入分布式一致性。
27. 作为熟悉 RuoYi 的维护者，我希望业务能力内部保留 Controller、Service、Mapper 和 Domain 等熟悉角色，以便降低结构迁移的学习成本。
28. 作为大模型编码代理，我希望先按业务能力定位代码，再进入技术角色，以便单张工单的规则、实现和测试保持局部集中。
29. 作为架构维护者，我希望知识库、企业文档、会话、问答记录和反馈之间只通过 application interface 协作，以便实现细节不会跨业务扩散。
30. 作为数据维护者，我希望六类 Sage Vault 业务状态只有一个 MySQL 所有者，以便重试、级联操作和隐私删除保持单一事实来源。
31. 作为知识管理员，我希望继续通过 RuoYi 角色获得一组管理权限，以便沿用现有账号、菜单和授权运维方式。
32. 作为普通用户，我希望登录后即可使用问答能力，以便无需额外维护一个普通问答角色。
33. 作为运维人员，我希望企业文档对象由其业务所有者直接管理，以便上传、状态转换和失败补偿不会跨越通用文件服务。
34. 作为运维人员，我希望异步任务状态保存在业务模块中，以便调度工具的更换不会改变业务真相或恢复语义。
35. 作为未来维护者，我希望能在需要统一调度时替换恢复触发 adapter，以便未来引入 XXL-JOB 而不重写任务状态机。
36. 作为前端开发者，我希望 Sage Vault 页面、状态、Java adapters 和专用 UI 按四个业务 feature 就近存放，以便一次业务变更保持局部。
37. 作为前端维护者，我希望业务状态默认局部化且不进入 RuoYi 全局 store，以便后端保持唯一权威并避免隐式跨页面耦合。
38. 作为普通用户，我希望停止生成与网络断流具有不同的跨端语义，以便问答记录状态准确且可恢复。
39. 作为大模型编码代理，我希望根 Agent 规则强制我先读取架构与代码框架文档，再导航到各端规则，以便新会话始终加载当前代码架构且不复制文档正文。
40. 作为 Java/Python 开发者，我希望根级机器可读 schema 是唯一 wire contract，以便双方可以独立实现且由 CI 阻止漂移。
41. 作为运维人员，我希望异步命令按至少一次投递并通过任务身份和尝试次数收敛，以便网络超时、重复与乱序不会破坏业务状态。
42. 作为维护者，我希望内部调用使用服务签名而不转发用户 token，以便 Python 不承担 RuoYi 授权职责。
43. 作为维护者，我希望跨端业务错误使用已注册的数值码，以便 HTTP、回调和 SSE 共享稳定且可治理的失败语义。
44. 作为 Windows 开发者，我希望本地 RTX 4060 使用显式 GPU profile，以便模型不会在 CUDA 故障时静默降级到不可接受的 CPU 性能。
45. 作为运维人员，我希望程序与模型制品分开发布，以便更新应用或模型时无需重复传输另一方。
46. 作为运维人员，我希望模型按不可移动 revision 和逐文件哈希离线分发，以便运行时不依赖 Hugging Face 网络或可变缓存。
47. 作为运维人员，我希望 GPU 与 CPU profile 使用各自官方 Torch wheel 来源和冻结依赖，以便目标平台安装来源可审核。
48. 作为普通用户，我希望查询嵌入优先于企业文档批量入库，以便后台处理不会无限阻塞问答。
49. 作为运维人员，我希望 liveness 与 readiness 表达不同故障，以便进程存活不被误认为模型可接流量。
50. 作为发布负责人，我希望 Windows GPU 和 Linux CPU 各有明确 smoke 门槛，以便性能基线与可移植性证明不会混淆。
51. 作为数据库维护者，我希望第一版业务 SQL 人工、增量且不可变地管理，以便暂不引入 Flyway 也不会出现多份 schema 真相。
52. 作为配置维护者，我希望各发布单元拥有配置字段和校验，而真实环境值来自 Nacos 或环境变量，以便秘密与环境事实不散落在代码中。
53. 作为项目负责人，我希望 Nacos Data ID 配置在本地保留副本且由我逐份裁定是否提交，以便敏感配置不会被自动纳入 Git。
54. 作为开发者，我希望项目基础组件只有一个编排入口，以便 RuoYi、MinIO 与 Milvus 基础设施不会由多个 compose 文件分别维护。
55. 作为契约维护者，我希望共同 schema 与语言相关测试分层归属，以便 Java、Python 可以独立验证而不复制 wire contract。
56. 作为测试开发者，我希望真实 Milvus 测试只由 RAG 模块拥有，以便 collection 和检索实现保持局部且 Java 不越过跨进程 seam。
57. 作为试点负责人，我希望质量评测资产有明确的跨系统 owner，以便正式结论来自完整用户 interface 而非内部模块捷径。
58. 作为安全维护者，我希望日志脱敏由两端就近测试并由端到端探针补充，以便既能定位实现问题又能证明系统没有正文泄漏。
59. 作为运维人员，我希望部署冒烟区分自动检查和人工供应商检查，以便稳定能力可重复执行而真实百炼验证保持显式。
60. 作为实施开发者，我希望 12 张工单各有唯一主落点、必要协作落点和验证落点，以便跨端切片仍有清晰协调 owner。
61. 作为代码维护者，我希望工单主落点由权威状态或关键不变量决定而非改动量决定，以便所有权不会随实现细节漂移。
62. 作为代码评审者，我希望新增、拆分、改名或改变主落点时同步更新工单导航，以便代码框架文档保持可信。
63. 作为架构维护者，我希望完整目录树只有一个权威文档，以便根 Agent 规则和端规则不会维护相互冲突的结构副本。
64. 作为新会话中的编码代理，我希望架构冲突必须被明确报告，以便不会静默绕过已经确认的依赖和准入规则。

## Implementation Decisions

- 遵循 ADR-0001：Java/Vue 负责业务中台，Python 负责 RAG 链路；浏览器只访问 Java，Java 与 Python 使用 Nacos 发现的内部 HTTP interface，Java 转发 SSE。
- 遵循 ADR-0002：本地 `bge-m3` 负责嵌入，Milvus 负责检索，百炼 `qwen-plus` 负责生成；生成供应商隐藏在可替换 adapter 后。
- Java 新业务归独立的 `ruoyi-kb-management` Maven/Spring Boot 发布单元；它由 `backend` 父工程直接聚合，与现有 `ruoyi-modules` 同级，不放入 `ruoyi-modules`，也不并入 `ruoyi-system`。
- V1 不把知识库、企业文档、会话、问答记录和反馈拆成各自独立的 Maven 模块或微服务。它们共享强一致业务流程且没有独立发布或扩容需求，拆分只会引入远程调用、补偿和状态复制。
- `ruoyi-kb-management` 内部按知识库、企业文档、会话、问答记录和反馈五个业务能力组织；每个能力内部可保留 Controller、Service、Mapper 和 Domain 等 RuoYi 技术角色。现有 RuoYi 模块保持原结构，不为统一外观而重构。
- Controller 只调用所属业务能力的 application interface；application 实现承载领域规则和流程，并通过 Mapper 或外部 adapter 完成副作用。Controller 不得直接访问 Mapper 或 adapter，Mapper 模型不得直接作为 HTTP 契约。
- 跨业务能力只允许调用对方公开的 application interface，不得直接导入对方的 Mapper、持久化对象、Service 实现或 adapter。发起级联操作的业务能力拥有顶层应用用例，并在本地事务内通过窄 interface 协调其他能力，避免循环依赖。
- 删除会话及其问答记录和反馈正文由会话能力拥有顶层应用用例；知识库级联删除由知识库能力拥有顶层应用用例。被协调能力提供窄的内部清理 interface，不反向依赖发起者的实现。
- `ruoyi-kb-management` 是知识库、企业文档、异步任务、会话、问答记录和反馈 MySQL 表的唯一所有者。其他 RuoYi 服务和 Python RAG 服务不得直接访问这些表；即使部署在同一 MySQL 实例，也只通过 owner 的 interface 协作。
- Sage Vault 业务表只引用稳定的 RuoYi 用户 ID，不复制账号、角色或部门资料，也不建立跨服务数据库外键。Python 只处理幂等命令和返回执行结果，不保存第二套业务状态。
- 企业文档原文件由 `ruoyi-kb-management` 内部的 MinIO adapter 直接管理。现有 `ruoyi-file` 不参与企业文档上传、读取或删除流程，也不承载 Sage Vault 业务规则。
- 保留 RuoYi 中唯一的“知识管理员”角色作为管理权限集合，不引入“知识库管理员”或知识库所有者概念。所有知识管理员可管理全部知识库、企业文档和反馈；普通问答能力授予所有已登录用户。
- `ruoyi-system` 继续拥有账号、角色、菜单和权限标识；`ruoyi-kb-management` 从可信安全上下文取得用户身份并校验权限标识，不维护管理员名单，不为每次业务请求远程查询 `ruoyi-system`。只有确需最新用户资料时才通过既有系统 interface 查询。
- 知识库、企业文档和反馈等管理操作通过模块自有的 `ManagementAudit` interface 向 `ruoyi-system` 现有操作审计能力发送白名单字段：操作人、对象类型与 ID、动作、时间、结果和请求 ID。不得对 Sage Vault endpoint 直接使用默认会序列化请求与响应的 RuoYi `@Log` 行为。
- 问答、检索、SSE 和异步任务诊断只写结构化技术日志，不进入操作审计表；日志可记录标识、阶段、分数、耗时、模型请求标识、进度、重试和错误，但不得复制问题、回答、企业文档片段或完整提示词。审计写入失败不回滚已成功的业务事务，但必须产生可告警的技术错误。
- Sage Vault 领域类型、状态机和业务流程不得放入 `ruoyi-common`。领域无关且至少被两个真实发布单元稳定复用的机制，才可进入一个职责明确的 `ruoyi-common-*` 模块；模块内部共享代码也应按需创建，不能成为循环依赖中转站或杂物目录。
- 文档入库、企业文档删除和知识库级联清理的任务记录、状态机、重试资格与恢复语义均由 `ruoyi-kb-management` 拥有。业务操作在本地事务中同时写入业务状态和持久化任务记录，事务提交后再派发幂等 Python 命令。
- V1 使用模块内 scheduler 扫描待发送、超时和可重试任务，并以数据库抢占、租约或乐观锁支持多实例安全执行。Python 回调携带任务身份和执行批次，Java 按当前权威状态处理重复或乱序结果。
- V1 不引入 XXL-JOB。模块保留窄的恢复触发 interface；未来 XXL-JOB 只能作为触发该 interface 的 adapter，不能保存企业文档任务状态、直接调用 Python 或编排业务状态机。现有 `ruoyi-job` 同样不拥有这些业务任务。
- Sage Vault 前端新增代码进入 `frontend/src/features/`，固定为 `conversations`、`knowledge-bases`、`enterprise-documents` 和 `feedback` 四个业务切片。每个 feature 就近拥有 `pages/`、局部状态或 feature store、Java adapter、DTO 映射、专用 UI 和公开入口；跨 feature 只能使用对方公开 interface。
- RuoYi 继续拥有登录、动态菜单、路由与按钮权限、标签页、全局设置、请求拦截、通用布局和全局通知。动态路由加载器在保留 `views/**/*.vue` 的同时扫描 `features/**/pages/*.vue`，后端菜单直接引用稳定的 feature 页面路径；现有 RuoYi 前端目录不做无关重构。
- 前端状态局部优先，仅在存在真实跨页面生命周期时提升为 feature 内 Pinia store。`conversations` store 拥有会话、问答记录和单个活跃流的前端生命周期；Sage Vault 业务状态不得进入 RuoYi 全局 store 或浏览器持久化缓存，后端状态始终权威。
- 普通 HTTP、上传和命令由所属 feature 的 Java adapter 封装。`conversations` 使用携带 Bearer token 的 `fetch` 流读取 Java SSE；用户停止生成必须同时调用显式取消 interface，`AbortController` 只释放连接。后端据此区分“已停止”与意外断流产生的“未完成”。
- feature 私有 UI 就近存放；只有领域无关、已有至少两个真实消费者且 interface 稳定的展示模块才能进入全局 `components/`。Sage Vault 业务逻辑不得进入全局 `utils/`、plugins、布局或 store。
- Agent 规则按端维护具体编码、依赖和必跑验证约束。根 Agent 规则按功能分类维护全局技术栈、版本状态和升级规则，同时作为强制导航入口：规划或修改代码前必须先读取系统架构与代码框架文档，随后按改动范围读取后端、前端或 AI 端规则；若代码、工单和架构文档冲突，必须停止并报告，不能静默绕过。根规则和端规则不得复制完整目录树或架构正文。
- 根目录 `contracts/java-python-rag/v1/` 是 Java-Python HTTP、回调和 SSE wire contract 的唯一权威来源，包含 OpenAPI、SSE JSON Schema、错误码注册表及共同样例。`.agents/rules/backend.md` 与 `.agents/rules/ai-modules.md` 只记录契约治理规则并链接实际资产。
- Java与Python共同维护 wire contract，但Java拥有业务身份、任务/尝试、状态裁决与取消语义，Python拥有RAG执行结果、流事件能力与安全诊断元数据。两端在 transport adapter 内手写模型并映射本端 application interface，V1 不生成代码，框架或供应商类型不得跨越 seam。
- 异步入库与清理采用至少一次投递和 `taskId`/`attempt` 幂等收敛。Python以 `202` 受理并回调结果；Java当前任务状态裁决重复、旧尝试和乱序回调。网络超时重投沿用当前尝试，知识管理员显式重试才递增 `attempt`。
- Java通过包含对象版本或校验和的限时预签名 URL 向Python提供企业文档。Python不持有MinIO管理权限；Java拥有原文件生命周期，Python只清理其解析产物和Milvus向量。
- Java为每条活跃问答创建 `generationId`，通过Nacos选择RAG实例并保持实例亲和。Python流返回项目自有事件，Java转发并持久化终态；显式取消返回原实例，实例不可达时Java保存“已停止”并记录取消未确认，意外断流保存“未完成”。
- Java/Python双向调用使用部署密钥签名及时间戳防重放，不转发用户token或角色。所有调用传播 `requestId`，并按链路补充 `taskId`/`attempt` 或 `generationId`；Nacos只负责发现，不是认证机制。
- HTTP状态表达请求层结果；业务错误使用 `8CCDDD` 六位整数并由 `contracts/java-python-rag/v1/errors.yaml` 注册含义、可重试性与HTTP映射。wire上禁止字符串错误码；Java独自决定重试、业务状态及用户文案。
- `v1` 内只允许向后兼容的可选字段、可忽略事件和新错误码；破坏性变更进入新版本目录。未知必需事件、未知错误码、非法状态组合和签名失败必须拒绝，不能猜测处理。
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
- 首个试点的前端、Java 和 Python 在 Windows 宿主机原生运行，Ubuntu 24 VMware 虚拟机只运行中间件。已核实的嵌入硬件是 RTX 4060 Laptop GPU（8 GB 显存）；GPU profile 使用 Python 3.12、FP16、单进程、单 worker、单模型实例、batch 4 和嵌入 semaphore 1，CUDA 不可用时保持 not ready，不允许静默降级。
- 显式 `cpu-dev` profile 使用 FP32、单 worker、batch 1 和 semaphore 1，仅供排障。Linux CPU 镜像只证明离线安装、启动和中文嵌入可移植性，不承担试点性能承诺。Ollama 不进入首个 profile，未来只有出现真实第二种实现时才通过既有嵌入 port 增加 adapter。
- 问答查询与企业文档入库共享一个模型实例和一个 GPU 执行槽。查询优先队列上限为 5，入库队列上限为 1 个批次；队列满时返回契约注册的可重试忙碌错误，不无限等待或加载第二份模型。五路并发回答是完整系统验收目标，不表示五路并行 GPU 推理。
- 程序与模型分开发布。模型按 Hugging Face 40 位 commit SHA 准备完整 snapshot 和逐文件 SHA-256 清单，再以 MinIO 不可变、版本化制品离线分发；运行时只从校验通过的显式目录加载，禁止使用可移动 revision、隐式缓存或访问公网。
- Windows GPU Torch wheel 只来自 PyTorch 官方 `cu128` 索引，Linux CPU wheel 只来自官方 CPU 索引；完整依赖来源和哈希由冻结 lock/export 与离线 wheelhouse 固化。FlagEmbedding 固定为已证实的 1.4.0；实际模型 SHA、Torch 最终精确版本及双平台解析证据延期到正式文档起草阶段验证，`2.7.1/cu128` 在此前只能视为候选。
- 直接依赖使用下界和下一不兼容版本上界；uv lockfile 精确固定完整依赖图。生产与 CI 使用 frozen lock，禁止部署时重新求解。
- Milvus 2.4.23 只配 PyMilvus `2.4.x`，禁止使用 PyMilvus 3.0。
- Python 补丁版本、uv/Ruff/mypy、Torch wheel 来源、模型 revision 和运行镜像精确锁定。Starlette、AnyIO、Torch、Transformers 等传递依赖只由 lockfile 固化，除非代码直接导入或需规避已证实缺陷。
- 代码、配置、测试与运维资产默认随拥有事实或实现的发布单元就近存放。仓库级资产必须跨至少两个发布单元、owner 明确、无法合理归入单端且只通过公开 interface 工作；每个仓库级区域必须说明 owner、消费者、准入/禁止内容、敏感数据规则和验证命令。禁止无 owner 的 `shared`、`common`、`utils`、通用 fixtures 或配置副本。
- V1 暂不引入 Flyway。Sage Vault 业务 schema 和种子数据由 Java 业务发布单元拥有，采用人工执行、不可变的增量编号脚本；已发布脚本不得改写，后续只追加。RuoYi 底座 SQL 不复制业务 schema，Java MySQL 集成测试复用同一组业务脚本。
- Java 只在本地 bootstrap 保存服务名、端口和 Nacos 导入规则；Python bootstrap 拥有默认值、类型与校验。真实地址、密码、密钥和模型路径来自 Nacos 或环境变量。Nacos Data ID 的实际配置保留本地副本并标明 Data ID、Group、环境及敏感性，是否提交 Git 由项目负责人逐份裁定，自动化不得擅自提交。
- 项目 base 组件由唯一开发编排统一启动 MySQL、Redis、Nacos、Sentinel、etcd、MinIO、Milvus 与 Attu；实施迁移时合并并删除原先分离的 RuoYi base 与 Milvus compose 入口。各发布单元仍拥有自己的 Dockerfile，基础设施服务的辅助配置可由新编排显式引用。
- 共同 Java-Python schema、错误注册表与样例归根级契约资产；语言无关兼容检查随契约，Java 和 Python consumer/provider 测试分别随两端模块，禁止复制 schema、样例或建立测试专用捷径。
- 正式质量评测由试点负责人拥有的跨系统评测资产承载，包含脱敏授权的数据集、参数、外部 runner 和小型报告模板，并且只通过 Java 对外 HTTP/SSE interface 得出 V1 结论。真实敏感材料、秘密和大体积结果不得提交 Git。
- 部署 smoke 作为跨系统资产，自动检查 Nacos、MySQL、MinIO、Milvus、Java/Python readiness、离线嵌入 profile、异步入库/清理补偿与关联 ID；真实百炼连通和流式观感保留为人工检查。根级 smoke 只调用公开业务或基础设施健康 interface，不直连私有表伪造成功。
- 每张实施工单必须有唯一主落点，由其最关键权威状态或不变量的 owner 决定；公开 interface 两侧构成必要协作落点，能观察完整用户承诺的最高 seam 构成验证落点。无法确定唯一 owner 时先拆分工单或明确协调模块，不得落入无 owner 的根级共享区。
- 12 张 V1 工单的主落点固定如下：空知识库问答、TXT 上传问答、批量上传、失败重试、企业文档删除、会话与流中断、反馈、知识库级联删除、角色审计与安全日志均由 Java 业务模块协调；多格式解析与百炼 adapter 由 Python RAG 模块协调；质量、容量与性能验收由跨系统评测资产协调。每张工单仍按导航表列出必要前端/Python/Java/契约/部署协作和验证 seam。

| 实施工单 | 唯一主落点 | 必要协作 | 主要验证 |
| --- | --- | --- | --- |
| 打通空知识库问答细线 | Java 的知识库、会话与问答能力 | 知识库/会话前端 feature；Python 回答与 transport adapters；跨端契约 | 浏览器到 Java 的 HTTP/SSE 系统验收与契约测试 |
| 上传并问答一篇 TXT 企业文档 | Java 的企业文档任务、状态与发布裁决 | 企业文档/会话前端 feature；Python 入库、回答、MinIO、嵌入和 Milvus adapters | 上传至回答系统验收、真实 Milvus 测试与契约测试 |
| 扩展 PDF、DOCX、MD 企业文档解析 | Python RAG 入库模块 | Java 上传校验与失败映射；企业文档前端 feature | 解析器集成测试与上传成功/失败系统验收 |
| 实现批量上传与同名原子校验 | Java 企业文档能力 | 企业文档前端 feature；Python 复用既有单文档命令 | Java/MySQL 测试与批量上传系统验收 |
| 完成文档失败重试与原子发布 | Java 企业文档任务状态机 | 企业文档前端 feature；Python 入库、临时产物、Milvus 发布/清理；跨端契约 | 状态/幂等/乱序测试、Milvus 测试与失败注入系统验收 |
| 完成文档删除与名称释放 | Java 企业文档删除与名称占用规则 | 企业文档前端 feature；Java MinIO adapter；Python 清理和 Milvus adapter；跨端契约 | Java/MySQL/MinIO、Milvus 及立即排除/最终清理系统验收 |
| 完善会话、历史与流式中断 | Java 会话、问答记录、单活跃回答与终态裁决 | 会话前端 feature；Python 回答、生成和取消 adapters；流/取消契约 | 历史与流状态系统验收及跨端流契约测试 |
| 建立用户反馈隐私闭环 | Java 反馈能力与正文授权规则 | 反馈/会话前端 feature；Java 审计 adapter | Java 权限/持久化测试及同意、可见性和删除系统验收 |
| 实现知识库级联删除 | Java 知识库能力与级联协调 | 知识库/企业文档/会话前端 feature；Java MinIO；Python 清理/Milvus；跨端契约 | Java 恢复/幂等/存储测试、Milvus 测试与级联系统验收 |
| 接入百炼 qwen-plus 生成适配器 | Python DashScope adapter | Python 回答和 bootstrap；前端复用出网提示；部署 smoke | 确定性假生成 adapter 自动化测试与真实百炼人工 smoke |
| 守住角色、审计与安全日志边界 | Java 权限、审计与安全日志规则 | 四个前端 feature；Python 安全日志；RuoYi adapters；跨系统泄漏探针 | 权限/审计系统验收、两端日志测试与端到端泄漏扫描 |
| 建立 V1 质量、容量与性能验收 | 试点负责人拥有的跨系统评测资产 | 部署 smoke、Milvus 集成测试及各端可观测性 | 只经 Java 对外 HTTP/SSE interface 的质量、隔离、容量、时延和部署验收 |

- 系统架构文档唯一描述当前/目标系统、运行关系、模块职责、数据所有权、依赖方向、关键 seam 与 ADR；代码框架文档唯一保存完整目标目录树、包/feature 结构、目录准入、interface/adapter 与配置/SQL/测试/部署落点，以及一行一工单的导航表。两者通过链接互引，不复制段落。
- 工单正文仍是需求与验收 checklist 的唯一权威。导航表只保存工单名称、唯一主落点、必要协作落点和验证落点，不绑定易变类名或函数名；新增、拆分、改名或改变主落点时必须在同一变更中更新，工单完成后保留追踪行。

## Testing Decisions

- 最高且主要的 seam 是 Java 对浏览器暴露的 HTTP/SSE interface。系统验收同时运行 Java 和 Python，使用真实 MySQL、MinIO、Milvus，并注入确定性假生成 adapter。
- `ruoyi-kb-management` 的业务测试经过各能力的 application interface，观察业务结果、持久化状态和 adapter 交互，不越过 interface 测试 Controller、Service 或 Mapper 的调用顺序。
- Java 集成测试使用真实 MySQL 验证知识库、企业文档、任务、会话、问答记录和反馈的本地事务、唯一约束、级联语义、幂等与乱序回调；MinIO 和 Python 在不属于测试目标时由 adapter 替身代替。
- 企业文档对象存储集成测试经过企业文档 application interface 使用真实 MinIO，验证成功提交、失败补偿、幂等删除和名称释放；不通过 `ruoyi-file` 建立第二条测试路径。
- 权限验收覆盖已登录普通用户的问答能力、知识管理员的全局管理能力、匿名拒绝、普通用户管理拒绝，以及不存在知识库级所有者授权。
- 恢复测试经过持久化任务与恢复触发 interface，验证事务提交后派发、崩溃后重发、多实例抢占、超时重试、重复回调和乱序回调；不绑定 Spring scheduler 或未来 XXL-JOB adapter 的内部实现。
- 审计测试经过 `ManagementAudit` interface 验证只发送白名单字段、任何正文均不进入操作审计或技术日志，以及审计 adapter 失败不会改变已经确定的业务结果。
- 前端系统验收经过浏览器到 Java 的真实 HTTP/SSE interface，覆盖动态菜单与权限、四个 feature 的关键路径、显式取消与意外断流的区别，以及反馈正文的授权可见性。少量前端测试只经过 feature 公开 composable/store interface 与 Java adapter seam，验证状态转换、DTO 映射和流事件归约。
- Python 单元测试只经过入库、回答和清理的 application interface，观察命令结果、流事件和外部调用，不断言 LangChain chain、内部步骤或 SDK 对象。
- HTTP/SSE 契约测试覆盖校验、错误映射、事件顺序、断连、取消、慢消费者和 Java 转发。
- Java与Python CI共同校验 schema、标准样例和本端序列化模型，并分别运行对方 provider/callback 的 consumer contract tests。所有假 provider、callback receiver 与受控流 provider 必须实现同一 wire contract，禁止测试专用捷径。
- 真实 Milvus 2.4.23 集成测试只由 Python RAG 模块拥有，并通过项目自有入库/检索 interface 覆盖 `knowledgeBaseId` 强制过滤、collection schema、整篇发布、即时检索排除和幂等删除；Java 与系统验收不直连 Milvus。
- 解析器集成测试使用 PDF、DOCX、Markdown 和 TXT 的成功与失败夹具，断言文本、页码和来源元数据，不测试第三方库实现。
- liveness 只检查进程和事件循环。完整 readiness 在启动或模型重载后校验模型 revision/哈希、目标设备、模型加载和固定中文嵌入，并缓存结果；日常 readiness 只读取状态。CUDA OOM、设备丢失或推理异常立即撤销就绪状态。
- Windows GPU smoke 要求冷启动至 ready 不超过 120 秒且不 OOM；中文输出为 1024 维、全部有限且 L2 归一化误差不超过 `1e-3`；连续 10 轮 batch 4 稳定成功；显存峰值不超过 7.5 GiB；结束后 readiness 仍通过。
- Linux CPU smoke 只验证模型和 wheelhouse 可离线校验、服务可启动、同一中文探针满足维度/有限值/归一化约束，不设置冷启动或吞吐门槛。模型 smoke 不替代五路并发、五秒首字和十五秒完成的完整系统验收。
- 日志脱敏采用“两端就近 + 端到端扫描”：Java 验证审计白名单与日志内容，Python 验证字段和异常清洗，跨系统评测以唯一探针贯穿上传、检索、问答、失败与取消并扫描汇集日志。根级探针不复制各端允许字段表，也不引入跨语言共享 logging 模块。
- 自动化测试不访问百炼；真实 DashScope 仅在部署后人工冒烟。
- 依赖升级必须重新生成 lockfile，并通过静态检查、上述契约/集成测试及目标模型镜像 smoke test；resolver 成功本身不构成兼容证明。
- 当前仓库没有可复用的 Sage Vault 自动化测试结构，既有 RuoYi 后端和 Vue 前端也未提供可直接沿用的业务测试先例；新测试优先建立上述少量高 seam 套件，不扩散实现耦合的 Controller、组件或调用顺序测试。

## Out of Scope

- 实现知识问答 V1 的业务功能或改变既有产品验收标准。
- 推翻 Java/Python 职责、百炼选择或数据出网决策。
- 为尚不存在的 AI 服务预先设计业务 interface、数据模型或共享库。
- 将 Java 五个业务能力拆为独立微服务，或把 Sage Vault 领域代码提升到 `ruoyi-common`。
- 引入 XXL-JOB、Celery、Python 业务数据库、OCR、重排、多模型后台配置或生产级高可用架构。
- 重构与 Sage Vault 无关的 RuoYi 通用模块。

## Further Notes

- 产品行为与质量指标由[通用企业文档问答 V1 规格](../knowledge-qa/spec.md)管理，本规格不复述其 89 条原始用户场景。
- Python 版本兼容事实和一手来源保留在[调研 Python 框架与版本兼容基线](issues/07-research-python-framework-version-baseline.md)；FlagEmbedding 已固定为 1.4.0，其余精确安装结果以未来提交且经目标环境验证的 lockfile 为准，Torch 候选在 smoke 通过前不构成基线。
- 架构、模块、运行 profile、横切资产和工单导航决策已收敛到本规格，可据此起草正式系统架构与代码框架文档。
- 模型实际 40 位 revision、Torch 最终精确版本、Windows CUDA/Linux CPU lock、离线 wheel 哈希和两套 smoke 证据仍是正式文档起草阶段的显式待办；在验证完成前不得把候选版本写成已确认基线。
