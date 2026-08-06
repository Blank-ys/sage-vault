# Sage Vault 代码框架

本文是 Sage Vault 完整目标目录、代码准入、interface/adapter 位置、横切资产归属和实施工单导航的权威文档。系统职责、运行时流程与数据所有权见 [系统架构](architecture.md)。

完整技术栈、版本状态、运行 profile 和升级规则统一见 [技术栈与版本](technology-stack.md)。根 [AGENTS.md](../AGENTS.md#技术栈) 只保留高频摘要；依赖或镜像变更必须同时更新机器清单和完整登记表。

文中的“目标”表示 V1 实施应逐步形成的结构；不存在的目录不要求在没有真实代码时预先创建。

## 目标目录

```text
sage-vault/
├── AGENTS.md
├── CONTEXT.md
├── docs/
│   ├── architecture.md
│   ├── code-framework.md
│   ├── technology-stack.md
│   ├── adr/
│   └── agents/
├── backend/
│   ├── pom.xml
│   ├── ruoyi-auth/
│   ├── ruoyi-gateway/
│   ├── ruoyi-modules/             # existing RuoYi module container
│   │   ├── ruoyi-system/
│   │   └── other existing RuoYi modules...
│   ├── ruoyi-kb-management/
│   │   ├── pom.xml
│   │   ├── sql/
│   │   │   ├── 001_schema.sql
│   │   │   └── 002_seed.sql
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/com/sagevault/kb/
│   │       │   └── resources/
│   │       └── test/
│   └── docker/                 # existing RuoYi image/support assets
├── frontend/
│   └── src/
│       ├── features/
│       │   ├── conversations/
│       │   ├── knowledge-bases/
│       │   ├── enterprise-documents/
│       │   └── feedback/
│       └── existing RuoYi platform directories...
├── ai-modules/
│   └── services/
│       └── rag/
│           ├── pyproject.toml
│           ├── uv.lock
│           ├── Dockerfile
│           ├── config/
│           ├── src/sage_vault_rag/
│           └── tests/
├── contracts/
│   └── java-python-rag/
│       ├── v1/
│       └── tests/
├── deploy/
│   ├── dev/
│   │   ├── docker-compose-base.yml
│   │   └── nacos-config/
│   └── smoke/
├── evaluation/
│   └── knowledge-qa/
│       ├── datasets/
│       ├── configs/
│       ├── runner/
│       └── reports/
└── .agents/
    └── rules/
        ├── backend.md
        ├── frontend.md
        └── ai-modules.md
```

## 根级准入规则

代码、配置、测试和运维资产默认随拥有其事实或实现的发布单元就近放置。只有同时满足以下条件才允许进入根级专用目录：

1. 跨至少两个发布单元。
2. 存在明确 owner。
3. 无法合理随某一个发布单元放置。
4. 只通过公开 interface 工作，不越过模块 seam。

每个根级区域必须说明 owner、消费者、允许/禁止内容、敏感数据规则和验证命令。禁止创建无 owner 的根级 `shared/`、`common/`、`utils/`、通用 fixtures 或配置副本。跨端复用不是把业务实现提升到根目录的理由。

## Java 发布单元

### 聚合与依赖

知识库管理由后端父工程直接聚合，与现有 `ruoyi-modules` 同级。它可以依赖 RuoYi 的安全、数据源、Swagger 等平台机制，但不得把 Sage Vault 业务类型或流程放入 `ruoyi-system`、`ruoyi-file` 或 `ruoyi-common`。

`com.sagevault.kb` 是规范根包。按业务能力优先、技术角色其次组织：

```text
com/sagevault/kb/
├── bootstrap/                   # Spring 启动与发布单元装配
├── knowledgebase/
│   ├── controller/
│   ├── service/                 # public XxxService interfaces
│   │   └── impl/                # XxxServiceImpl use-case implementations
│   │   └── port/                # narrow external-system interfaces
│   ├── domain/
│   ├── mapper/
│   └── adapter/
├── document/
│   ├── controller/
│   ├── service/
│   │   └── impl/
│   │   └── port/
│   ├── domain/
│   ├── mapper/
│   └── adapter/                 # MinIO and Python command/callback adapters
├── conversation/
│   ├── controller/
│   ├── service/
│   │   └── impl/
│   │   └── port/
│   ├── domain/                  # includes Java-owned AnswerEvent
│   ├── mapper/
│   └── adapter/                 # Python stream/cancel adapter
├── qarecord/
│   ├── service/
│   │   └── impl/
│   │   └── port/
│   ├── domain/
│   └── mapper/
├── feedback/
│   ├── controller/
│   ├── service/
│   │   └── impl/
│   │   └── port/
│   ├── domain/
│   ├── mapper/
│   └── adapter/
└── platform/
    ├── security/                 # RuoYi security context adapter
    ├── audit/                    # ManagementAudit adapter
    ├── error/                    # BusinessException, ErrorCode and HTTP handler
    └── observability/            # safe technical logging mechanisms
```

异步任务随 `document` 或 `knowledgebase` 能力存放，不创建可容纳任意任务的公共包。只有发布单元级平台机制可进入 `platform`；领域规则、业务状态机和业务 DTO 不得进入其中。

### Java 依赖规则

- Controller 只调用所属能力的 `XxxService` interface；该 interface 是本能力的公开 application interface。
- 有真实 HTTP 入口的类命名为 `XxxController`，公开接口命名为 `XxxService`，实现命名为 `XxxServiceImpl`，MyBatis 持久化接口命名为 `XxxMapper`；不保留 `XxxApplication` 或无 owner 的 Service 命名，也不为无 HTTP 入口的能力创建空 Controller。
- 浏览器与 Java 之间的 Request/Response 随所属能力放在 `service`，作为公开 `XxxService` 契约；只有 HTTP 契约与 Service 契约真实分化时才增加 controller-private DTO。
- `XxxServiceImpl` 拥有流程。模块内 MySQL 持久化直接通过本能力的 MyBatis Mapper 产生副作用；外部系统通过 port/adapter 接入。
- Mapper 只使用独立的 `XxxEntity` 持久化模型；Request/Response 不得作为 Mapper 参数或返回值，Entity 也不得作为 HTTP 或跨进程契约。公开 Request/Response 保持不可变，Entity 可为 MyBatis generated-key 回填提供可写属性，但不承载业务行为。
- `XxxServiceImpl` 负责业务模型与 Entity 的显式映射；出现真实的重复或复杂映射前不预建通用 converter。
- MyBatis Mapper 接口只声明持久化方法，SQL 统一放在 `resources/mapper/<capability>/` 的 XML 中；XML 使用完整接口名作为 namespace、显式 `resultMap` 和列清单，不混用 SQL 注解或 `SELECT *`。
- 自增主键使用 MyBatis generated-key 映射；名称规范化等业务值由 `XxxServiceImpl` 或业务类型计算，Mapper 只持久化结果。
- 跨能力只导入对方公开 `XxxService` interface；不得导入 Mapper、持久化对象、`XxxServiceImpl` 或 adapter。
- 会话能力通过两个公开 application interface 暴露：`ConversationService` 承载会话 CRUD 与历史投影，`AnswerSessionService` 承载回答生命周期（开始并流式返回、状态查询、显式停止）。`ConversationServiceImpl` 直接使用 `ConversationMapper` 访问本能力数据，并通过 `QaRecordService`、`FeedbackService` 完成历史投影；`AnswerSessionServiceImpl` 收敛发起事务、SSE 事件流、停止信号、RAG 取消与终态裁决，通过 `ConversationMapper` 校验归属、`KnowledgeBaseService` 校验可用性、`QaRecordService` 创建和裁决记录、RAG port 发起和取消回答。两者都不进行 Service 自调用。
- `AnswerEvent` 是会话回答流程的 Java-owned domain model，放在 `conversation/domain`；RAG adapter 将 Python wire event 映射为它。问答记录能力只接收明确的状态裁决，不依赖流事件类型；不创建根级 `model`、`event` 或 `shared` 包。
- 领域状态使用带不可变 `desc` 描述的 enum；MyBatis 按 enum `name()` 持久化，`desc` 不参与状态判断或数据库存储。Issue 01 不自动把描述扩展到 HTTP Response；有真实展示需求时由 Response 显式映射。
- Issue 01 保持单一 `KnowledgeBaseService`，并以 `requireAvailable(knowledgeBaseId)` 等意图化窄方法服务跨能力协作；消费者不得取得完整 Response 后自行复制可用性规则。出现真实且稳定的权限或消费者分化前不预拆 Query/Management Service。
- 知识库能力协调知识库级联删除；会话能力协调会话、问答记录和反馈正文删除。被协调能力只提供窄清理 interface。
- MinIO、Python、RuoYi 审计和安全上下文都位于 adapter seam 后。
- 每个能力的 `service/port` 只放 `XxxServiceImpl` 调用外部系统的窄 interface，adapter 实现对应 port；Mapper 是模块内 MySQL persistence seam，不属于 port。不得创建发布单元根级 `ports` 包。
- Java/Python 请求、响应和 SSE wire model 只存在于调用 Python 的 owner 能力 adapter 内，默认作为 adapter 私有类型；只有契约测试等真实消费者需要复用时才拆入该 adapter 的 `transport` 子包。port 只暴露 Java 项目自有类型，不暴露 HTTP、SSE、Python 或第三方 SDK 类型。
- 不得直接使用会序列化 Sage Vault 请求/响应正文的默认审计注解。
- 发布单元级 `BusinessException`、`ErrorCode` 与 `GlobalExceptionHandler` 统一放在 `platform/error`，不随能力复制。Mapper 和 adapter 故障由 Service 边界映射为已注册业务错误；未预期异常只返回安全通用文案并记录脱敏异常日志。

### SQL 与配置

V1 不使用 Flyway。业务 SQL 是人工执行、不可变的增量编号脚本：首个 schema 和种子脚本发布后不得改写，后续只追加。RuoYi 底座 SQL 可以链接业务安装说明，但不得复制业务 schema。Java MySQL 集成测试必须复用同一组脚本。

`ruoyi-kb-management` 显式声明 MyBatis starter，但使用后端父 POM 已锁定的版本且不在模块 POM 重复版本号；不引入 MyBatis-Plus。移除全部 `JdbcTemplate` 使用后移除模块直接声明的 JDBC starter，同时保留数据源机制和 MySQL 驱动。模块在 `bootstrap/MyBatisConfiguration` 中通过 `@MapperScan("com.sagevault.kb.**.mapper")` 统一注册 Mapper，不扩大 RuoYi 公共 `com.ruoyi.**.mapper` 扫描范围，也不在每个 Mapper interface 重复添加 `@Mapper`。模块自有配置显式声明 `mybatis.mapper-locations: classpath*:mapper/**/*.xml`，不依赖环境中未登记的隐式扫描配置。

`XxxServiceImpl` 使用 Spring `@Service`，外部 adapter 使用对应的 Spring stereotype，并统一通过构造器注入；Mapper 由模块私有 `@MapperScan` 注册，不添加 stereotype。跨能力只注入公开 `XxxService` interface。bootstrap 只装配 `WebClient.Builder` 等发布单元级基础设施对象，不手工 `new` 业务 Service 或唯一实现的 adapter。

本地 `bootstrap.yml` 只包含服务名、端口、profile 和 Nacos 导入规则。业务配置字段、默认值和校验随模块代码维护；真实地址、密码和密钥只从 Nacos 或环境变量取得。

## Python RAG 发布单元

```text
services/rag/
├── pyproject.toml
├── uv.lock
├── Dockerfile
├── config/                       # committable, non-secret templates
├── src/sage_vault_rag/
│   ├── bootstrap/                # settings, validation, assembly, profiles
│   ├── transport/http/           # HTTP/SSE and contract mapping only
│   ├── application/
│   │   ├── indexing/
│   │   ├── answering/
│   │   └── cleanup/
│   ├── model/                    # project-owned execution types
│   ├── ports/                    # MinIO, vector, embedding, generation, callback...
│   └── adapters/
│       ├── minio/
│       ├── milvus/
│       ├── bge_m3/
│       ├── dashscope/
│       ├── java_callback/
│       └── nacos/
└── tests/
    ├── unit/
    ├── contract/
    ├── integration/
    │   ├── parsers/
    │   └── milvus/
    └── smoke/
```

- FastAPI 与 Pydantic transport 类型停留在 `transport/http`。
- LangChain 只存在于 application 实现或内部 adapter，不进入 port、执行模型或 wire contract。
- application 只依赖项目自有 model 与 ports；adapter 依赖方向指向 ports。
- 解析步骤和 LangChain chain 默认是内部实现，不因测试方便扩展公开 interface。
- 每个 AI 发布单元独立锁依赖和构建，服务间禁止源码导入。V1 不预建 Python `shared` 或 `common`。
- `bge_m3` adapter 封装模型目录、Torch、设备、精度、batch、队列和健康状态；application 只看文本用途、归一化向量和分类资源错误。
- `dashscope` adapter 把供应商流转换为项目自有生成结果；SDK 类型和凭据不得离开 adapter。

## 前端 feature

每个 feature 可按真实需要选择以下子目录，不要求空目录占位：

```text
features/<feature>/
├── pages/                         # stable dynamic-route targets
├── components/                    # feature-private UI
├── composables/
├── store/                         # only real cross-page lifecycle
├── api/                           # Java adapter and DTO mapping
├── model/                         # feature-owned UI types/state
└── index.js                       # narrow public entry
```

- 页面只调用 feature interface，不拼装请求或解析原始 SSE frame。
- feature 私有 UI 和业务逻辑不得进入全局 `components`、`utils`、plugins、layout 或 global store。
- 只有领域无关、至少两个真实消费者且 interface 稳定的展示模块可进入全局 `components`；它不得读取 feature store。
- 跨 feature 只能从公开入口导入。`conversations` 可以消费 `knowledge-bases` 的只读选择 interface，不得读取其私有 store 或 adapter。
- 默认使用页面/composable 局部状态。只有真实跨页面生命周期才创建 feature store；`conversations` 拥有当前会话、记录和单活跃流的前端生命周期。
- 普通 HTTP、分页、上传和命令由所属 feature 的 `api` adapter 封装。SSE 使用 `fetch` 和 `AbortController`，显式取消命令不能被连接中断替代。
- 动态路由同时扫描现有 `views/**/*.vue` 与目标 `features/**/pages/*.vue`，菜单记录直接引用稳定页面路径。

## 跨端契约

```text
contracts/java-python-rag/
├── v1/
│   ├── openapi.yaml
│   ├── events/                    # named SSE event schemas
│   ├── errors.yaml
│   └── examples/
└── tests/                         # language-neutral schema/example checks
```

Java 和 Python 共同维护该契约，但分别在本端 transport adapter 内手写模型。两端 consumer/provider 测试随各自发布单元存放并引用根契约，禁止复制 schema、样例或建立绕过契约的测试 interface。

兼容性规则：`v1` 只允许增加兼容性可选字段、声明可忽略事件和新注册错误码；删除、改名、语义改变或收紧校验属于破坏性变更，必须进入新版本。

## 部署、配置与评测资产

### 开发基础组件

`deploy/dev/docker-compose-base.yml` 是 MySQL、Redis、Nacos、Sentinel、etcd、MinIO、Milvus 和 Attu 的唯一项目 base 编排。实施迁移时合并并删除旧的 RuoYi base 与 Milvus compose 入口，避免双份真相。各发布单元 Dockerfile 仍随发布单元存放；现有 MySQL、Redis、Nacos 辅助文件可由新编排明确引用。

Nacos Data ID 的实际配置在 `deploy/dev/nacos-config/` 保留本地副本，并标注 Data ID、Group、环境及是否含秘密。项目负责人逐份裁定是否提交 Git；未批准文件必须保持未跟踪或忽略，自动化不得擅自加入版本控制。

### 部署 smoke

`deploy/smoke/` 只负责编排和汇总跨系统检查：Nacos 注册、存储连通、Java/Python readiness、离线嵌入 profile、异步入库/清理补偿和关联 ID。真实百炼连通与流式观感属于人工清单。smoke 只调用公开业务或基础设施健康 interface，不直连私有表伪造成功。

### 质量评测

`evaluation/knowledge-qa/` 由试点负责人拥有：

- `datasets`：脱敏且获授权的企业文档夹具、问题与人工标注。
- `configs`：评测参数组合。
- `runner`：只通过 Java 对外 HTTP/SSE interface 执行评测。
- `reports`：报告模板与小型基线摘要。

真实敏感材料、秘密和大体积结果不得提交 Git。Python 内部调优工具可随 RAG 存放，但正式 V1 质量和性能结论只能来自跨系统评测。

## 测试落点

| 目标 | Owner 与 seam |
| --- | --- |
| 完整用户行为 | 浏览器到 Java 的真实 HTTP/SSE 系统验收 |
| Java 业务规则 | 各能力 application interface |
| Java 持久化与恢复 | 真实 MySQL、业务 SQL、任务恢复 interface |
| 企业文档原文件 | 企业文档 application interface + 真实 MinIO |
| 前端状态与映射 | feature 公开 composable/store interface + Java adapter seam |
| 跨端 wire 行为 | 根 schema/样例检查 + 两端 consumer/provider tests |
| Python RAG 行为 | indexing/answering/cleanup application interfaces |
| 解析格式 | Python parser integration tests with representative fixtures |
| 向量隔离和生命周期 | Python RAG 经过项目 port 使用真实 Milvus |
| 模型运行 | Windows GPU 与 Linux CPU smoke profiles |
| 日志脱敏 | 两端就近测试 + 跨系统唯一探针扫描 |
| 真实百炼 | 部署后的人工 smoke，不进入自动化 |

测试断言外部行为，不断言 Controller/Mapper 调用顺序、Vue 私有方法、Pinia 内部实现、LangChain chain 或第三方 SDK 对象。仓库当前没有 Sage Vault 自动化测试先例；新套件优先建立少量高 seam 验证，不扩散浅层实现测试。

MyBatis 改动以 Service 行为测试和真实 MySQL Mapper 集成测试互补验证。Mapper 集成测试复用生产 SQL，至少覆盖 XML 扫描与绑定、显式映射、自增主键回填、知识库名称唯一约束、会话字段映射，以及问答记录条件更新与迟到事件保护；它不能替代 Java/Python 契约测试或浏览器经 Gateway 到 Java、由 Java 分别协作 MySQL 与 Python 的系统验收。Python 不访问 MySQL。

## 实施工单导航

主落点由最关键权威状态或不变量的 owner 决定，不按改动量判断。协作落点只列必须跨越的公开 interface 两侧；验证落点选择能观察完整用户承诺的最高 seam。

| 实施工单 | 唯一主落点 | 必要协作落点 | 验证落点 |
| --- | --- | --- | --- |
| [打通空知识库问答细线](../.scratch/V1.0(knowledge-qa)/issues/01-empty-knowledge-base-qa-tracer.md) | Java 的知识库、会话与问答能力 | 知识库/会话前端 feature；Python 回答与 transport adapters；跨端契约 | 浏览器到 Java 的 HTTP/SSE 系统验收；契约测试 |
| [上传并问答一篇 TXT 企业文档](../.scratch/V1.0(knowledge-qa)/issues/02-upload-and-answer-txt-document.md) | Java 的企业文档任务、状态与发布裁决 | 企业文档/会话前端 feature；Python 入库、回答、MinIO、嵌入和 Milvus adapters | 上传至回答系统验收；真实 Milvus；契约测试 |
| [扩展 PDF、DOCX、MD 企业文档解析](../.scratch/V1.0(knowledge-qa)/issues/03-support-pdf-docx-markdown.md) | Python RAG 入库模块 | Java 上传校验与失败映射；企业文档前端 feature | 解析器集成测试；上传成功/失败系统验收 |
| [实现批量上传与同名原子校验](../.scratch/V1.0(knowledge-qa)/issues/04-batch-upload-and-name-validation.md) | Java 企业文档能力 | 企业文档前端 feature；Python 复用单文档命令 | Java/MySQL；批量上传系统验收 |
| [完成文档失败重试与原子发布](../.scratch/V1.0(knowledge-qa)/issues/05-retry-and-atomic-document-publication.md) | Java 企业文档任务状态机 | 企业文档前端 feature；Python 入库和 Milvus 发布/清理；跨端契约 | 状态/幂等/乱序；Milvus；失败注入系统验收 |
| [完成文档删除与名称释放](../.scratch/V1.0(knowledge-qa)/issues/06-delete-document-and-release-name.md) | Java 企业文档删除与名称占用 | 企业文档前端 feature；Java MinIO；Python 清理/Milvus；跨端契约 | Java/MySQL/MinIO；Milvus；删除系统验收 |
| [完善会话、历史与流式中断](../.scratch/V1.0(knowledge-qa)/issues/07-conversations-history-and-stream-control.md) | Java 会话、问答记录、单活跃回答和终态裁决 | 会话前端 feature；Python 回答、生成、取消；流/取消契约 | 历史与流状态系统验收；流契约测试 |
| [建立用户反馈隐私闭环](../.scratch/V1.0(knowledge-qa)/issues/08-user-feedback-privacy-loop.md) | Java 反馈能力与正文授权 | 反馈/会话前端 feature；Java 审计 | 权限/持久化；同意、可见性和删除系统验收 |
| [实现知识库级联删除](../.scratch/V1.0(knowledge-qa)/issues/09-cascade-delete-knowledge-base.md) | Java 知识库能力与级联协调 | 三个相关前端 feature；Java MinIO；Python 清理/Milvus；跨端契约 | 恢复/幂等/存储；Milvus；级联系统验收 |
| [接入百炼 qwen-plus 生成适配器](../.scratch/V1.0(knowledge-qa)/issues/10-bailian-qwen-generation-adapter.md) | Python DashScope adapter | Python 回答/bootstrap；前端出网提示；部署 smoke | 假生成 adapter 自动化；真实百炼人工 smoke |
| [守住角色、审计与安全日志边界](../.scratch/V1.0(knowledge-qa)/issues/11-roles-audit-and-safe-logging.md) | Java 权限、审计和安全日志规则 | 四个前端 feature；Python 安全日志；RuoYi adapters；泄漏探针 | 权限/审计系统验收；两端日志；端到端泄漏扫描 |
| [建立 V1 质量、容量与性能验收](../.scratch/V1.0(knowledge-qa)/issues/12-v1-quality-capacity-acceptance.md) | 试点负责人拥有的跨系统评测资产 | 部署 smoke；Milvus 集成测试；各端可观测性 | 只经 Java HTTP/SSE 的质量、隔离、容量、时延和部署验收 |

工单正文是需求与验收 checklist 的唯一权威；本表不复制 checklist，也不绑定易变类名或函数名。新增、拆分、改名或改变主落点时，必须在同一变更中更新本表。工单完成后保留该行，只更新状态或稳定实现入口，以保留追踪。

## 新工单归属算法

1. 找出最关键的权威状态或不变量，其 owner 是唯一主落点。
2. 列出必须跨越的公开 interface；只有 interface 两侧是协作落点。
3. 选择能观察完整用户承诺的最高 seam 作为验证落点。
4. 无法确定唯一 owner 时先拆分工单，确实不可拆时显式指定协调模块。
5. 根级资产必须继续满足根级准入规则。

代码评审必须检查工单导航是否随架构或工单变化同步更新。

## 文档与 Agent 规则

- 根 `AGENTS.md` 强制编码代理在规划或修改代码前读取本文与 [系统架构](architecture.md)。
- 修改后端、前端或 AI 代码时，再读取对应 `.agents/rules/` 文件。
- 端规则只保存端内编码与必跑验证，不复制本文目录树。
- 若代码、工单和架构文档冲突，必须停止并报告，不能静默绕过。
