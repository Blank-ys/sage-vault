# Knowledge Base Management MyBatis Refactor

Status: ready-for-agent

## Problem Statement

Issue 01 已经建立知识库、会话和空知识库拒答的第一条 Java walking skeleton，但 Java 模块当前直接使用 `JdbcTemplate`，并以横向的 application、transport 和 persistence adapter 包组织。这个形状既没有采用后端父工程已锁定的 MyBatis 技术栈，也没有落实 Sage Vault 已确认的“业务能力优先、技术角色其次”目录与依赖规则。

开发者希望借鉴 RuoYi System 清晰的 Controller-Service-Mapper 调用方式、Mapper interface 与 XML SQL 分离方式以及 Spring 自动注入机制，同时避免把 RuoYi System 的根级技术分层、跨能力 Mapper 依赖或持久化对象贯穿 HTTP 的做法复制到 Sage Vault。重构还必须保留 Issue 01 已有的权限、业务错误、Java/Python SSE 契约和用户可观察行为，并在真实 MySQL 上证明 MyBatis 映射与状态裁决正确。

## Solution

在 Issue 01 完成之前，只重构它已经引入的知识库、会话和问答记录三个能力。每个能力使用 `controller`、`service`、`service.impl`、`service.port`、`domain`、`mapper` 和必要的 `adapter` 组织代码；公开用例接口统一命名为 `XxxService`，实现统一命名为 `XxxServiceImpl`。

模块拥有的 MySQL 持久化由 ServiceImpl 直接调用 MyBatis Mapper，不再保留 Repository port 和 persistence adapter。Mapper interface 只声明持久化方法，SQL 统一放在能力对应的 XML 中，并使用独立的可写 `XxxEntity` 完成映射和自增主键回填。RAG、RuoYi 审计等外部系统仍通过能力内 port 与 adapter 隔离。

会话能力通过单一 `ConversationService` 公开创建会话和发起问题。它使用自身 Mapper 校验会话归属，通过 `KnowledgeBaseService.requireAvailable` 复用知识库可用性规则，通过 `QaRecordService` 的短事务创建和裁决问答记录，并在数据库事务之外调用 Python RAG。知识库列表继续不分页，由 MySQL 过滤可用状态；知识管理员列表和普通用户可用列表都按更新时间倒序稳定排列。

## User Stories

1. 作为普通用户，我希望重构后仍能看到所有可用知识库，以便继续选择问答范围。
2. 作为普通用户，我希望可用知识库按最近更新时间倒序显示，以便优先看到最近维护的内容范围。
3. 作为普通用户，我希望相同更新时间的知识库仍有稳定顺序，以免列表在请求之间随机跳动。
4. 作为普通用户，我希望不可用、删除中或删除失败的知识库不出现在可选列表中，以免进入不可回答的范围。
5. 作为普通用户，我希望仍能为可用知识库创建会话，以便开始问答。
6. 作为普通用户，我希望不能访问其他用户的会话，以保护自己的问答记录。
7. 作为普通用户，我希望发起问题前系统再次检查知识库是否可用，以免在状态变化后继续向不可用知识库提问。
8. 作为普通用户，我希望空知识库问题仍收到 `started` 和明确的拒答事件，以便理解系统已处理但没有可用文档。
9. 作为普通用户，我希望 RAG 流发生错误时问答记录被标记为未完成，以免错误结果被误认为有效拒答。
10. 作为普通用户，我希望迟到的流事件不能覆盖已经确定的问答结果，以保持历史记录可信。
11. 作为普通用户，我希望重复到达的同一终态事件不会造成错误或重复状态变化，以便网络重试安全收敛。
12. 作为普通用户，我希望浏览器仍只连接 Java，而不是直接连接 Python 或数据库，以保持认证和业务裁决一致。
13. 作为知识管理员，我希望仍能创建、查看和修改知识库，以便完成 Issue 01 的管理行为。
14. 作为知识管理员，我希望知识库名称忽略首尾空白和大小写后仍全局唯一，以免产生视觉重复的知识库。
15. 作为知识管理员，我希望并发创建等价名称时数据库仍能拒绝重复，以免预查询竞态破坏唯一性。
16. 作为知识管理员，我希望名称冲突继续返回明确的业务错误，而不是暴露数据库异常。
17. 作为知识管理员，我希望完整知识库列表按最近更新时间倒序显示，以便优先管理最近变化的知识库。
18. 作为知识管理员，我希望名称、描述或状态变化会更新排序时间，以便列表反映真实维护活动。
19. 作为知识管理员，我希望现有最小白名单审计调用继续存在，以免重构意外删除已有安全边界。
20. 作为知识管理员，我希望本次重构不引入尚未承诺的审计可靠性、重试或事务顺序，以免 Issue 01 被后续需求拖延。
21. 作为开发者，我希望 Controller 只依赖所属能力的公开 Service 接口，以便替换实现而不改变 HTTP 层。
22. 作为开发者，我希望跨能力协作只调用对方公开 Service 接口，以便阻止 Mapper、Entity 和 ServiceImpl 越过 owner 边界。
23. 作为开发者，我希望 MySQL CRUD 直接使用 MyBatis Mapper，以便消除当前简单场景中重复的 Repository port 与 persistence adapter。
24. 作为开发者，我希望 Mapper interface 与 XML SQL 分离，以便保持与 RuoYi System 相近的持久化维护方式。
25. 作为开发者，我希望 Mapper 只使用独立 Entity，以免数据库字段变化直接污染 HTTP Request/Response。
26. 作为开发者，我希望公开 Request/Response 保持不可变，以便 Service 契约清晰且不受主键回填机制影响。
27. 作为开发者，我希望自增主键由 MyBatis 回填到持久化 Entity，以便插入后构造稳定的公开 Response。
28. 作为开发者，我希望状态使用带描述的枚举并按枚举名称持久化，以便代码可读且数据库值稳定。
29. 作为开发者，我希望未知数据库状态读取失败而不是静默降级，以便尽快发现数据或版本不一致。
30. 作为开发者，我希望 RAG 请求、响应和 SSE wire model 留在会话 RAG adapter 内，以免 Python transport 类型污染 Service 契约。
31. 作为开发者，我希望 Java 项目自有 `AnswerEvent` 归属会话能力，以免创建无 owner 的根级 model 或 shared 包。
32. 作为开发者，我希望 Python 永远不访问 Java 的 MySQL 业务表，以保持业务状态只有 Java 一份权威。
33. 作为开发者，我希望流式回答不持有长数据库事务，以免外部 HTTP/SSE 占用连接和锁。
34. 作为开发者，我希望问答记录状态裁决运行在独立短事务中，以便每次状态更新快速提交并可独立恢复。
35. 作为开发者，我希望 Service 和 adapter 由 Spring 组件扫描与构造器注入，以便事务代理和依赖关系正常生效。
36. 作为开发者，我希望 Mapper 由模块私有扫描配置统一注册，以免修改 RuoYi 公共扫描范围或逐接口重复注解。
37. 作为开发者，我希望模块显式声明父 POM 管理的 MyBatis starter，以便真实依赖清晰且版本保持一致。
38. 作为开发者，我希望不引入 MyBatis-Plus，以免为当前简单 SQL 增加未经批准的技术和版本维护面。
39. 作为开发者，我希望测试复用生产 schema SQL，以免测试专用数据库结构掩盖部署问题。
40. 作为开发者，我希望重构后重新执行 Java、契约、前端和系统验证，以免沿用已经失效的历史证据。

## Implementation Decisions

- 本规格是未完成 Issue 01 的完成工作，不是 Issue 01 之后的独立行为变更。只迁移知识库、会话和问答记录，不预建企业文档、反馈或其他未来能力空壳。
- Java 根包保持 `com.sagevault.kb`，内部按业务能力优先、技术角色其次组织。每个已实现能力使用 controller、service、service implementation、domain、mapper 和必要 adapter；外部依赖 port 位于所属能力的 service/port。
- 有真实 HTTP 入口的 Controller 统一命名为 `XxxController`；公开 application interface 统一命名为 `XxxService`，实现统一命名为 `XxxServiceImpl`，MyBatis Mapper 统一命名为 `XxxMapper`。不保留 `XxxApplication`、无 owner 的 `AnswerService` 或其他旧式命名；没有真实 HTTP 入口的能力不为目录或命名对称创建空 Controller。ServiceImpl 使用 Spring Service stereotype 和构造器注入；跨能力只注入 Service interface。
- Controller 只依赖所属能力的公开 Service。浏览器与 Java 之间的 Request/Response 属于该 Service 的公开契约，并保持不可变；只有 HTTP 与 Service 契约真实分化时才新增 Controller 私有 DTO。
- 模块内 MySQL persistence 由 ServiceImpl 直接调用 MyBatis Mapper，不保留 Repository port 或 persistence adapter。Mapper 不拥有业务规则、业务错误文案或事务编排。
- Mapper interface 只声明方法，SQL 全部放在能力对应的 XML。XML 使用完整 interface 名作为 namespace、显式 result map、显式列清单和 generated-key 映射，不混用 SQL 注解或通配列查询。
- Mapper 使用独立 `XxxEntity`。Entity 可以为自增主键回填提供可写属性，但不承载业务校验，不作为 HTTP、跨能力或跨进程契约；避免使用会扩大语义的通用数据注解。
- ServiceImpl 显式完成业务模型、公开 Response 与 Entity 之间的映射。在出现真实重复或复杂映射前不增加通用 converter 或 MapStruct 层。
- 模块显式依赖父 POM 管理的 MyBatis starter，不声明重复版本，不引入 MyBatis-Plus。所有 `JdbcTemplate` 使用移除后，模块直接 JDBC starter 随之移除；数据源机制和 MySQL driver 保留。
- Mapper 通过模块私有 MyBatis 配置和 `@MapperScan` 统一注册，不修改 RuoYi 公共扫描范围，也不逐个添加 Mapper 注解。模块显式配置 XML mapper location。
- bootstrap 只装配发布单元级基础设施对象；业务 Service 与唯一实现的 adapter 使用 Spring 自动发现，不在配置类中手工构造。
- 单一 `ConversationService` 公开创建会话和发起问题。其实现直接使用 `ConversationMapper` 访问本能力数据，不自调用 Service，也不创建重复的回答 Service。
- `KnowledgeBaseService` 保持单一公开接口，并提供 `requireAvailable(id)` 等意图化窄方法。会话能力不得取得完整知识库 Response 后复制可用性规则；当前不预拆查询与管理 Service。
- `QaRecordService` 拥有问答记录创建和状态裁决。`STARTED` 表示 Java 已接受生成；Python `started` 事件只向浏览器转发，不再次写数据库，也不新增 `PENDING` 或 `ACCEPTED`。
- Issue 01 的问答记录终态迁移仅为 `STARTED` 到 `REFUSED` 或 `UNFINISHED`。Mapper 使用条件更新；Service 根据零行更新区分同终态幂等、不同终态冲突和记录缺失。迟到事件不得覆盖终态。
- 问答记录创建和终态裁决使用独立短事务。知识库创建/修改与会话创建当前均为单条 SQL，不机械添加事务；发起问题的流式方法不持有覆盖 RAG HTTP/SSE 的数据库事务。
- RAG port 位于会话能力，使用 Java 项目自有类型。Java/Python 请求、响应和 SSE wire model 只在 RAG adapter 内存在，默认保持 adapter 私有；只有真实契约测试消费者需要时才拆成 transport 子包。
- Java 项目自有 `AnswerEvent` 归属会话 domain。问答记录能力接收明确状态命令，不依赖流事件类型；不创建根级 model、event、ports、constants 或 shared 包。
- 知识库名称由业务值类型完成 trim 和大小写规范化。Service 先预检名称冲突以提供清晰反馈，MySQL normalized-name 唯一约束作为并发最终裁决；Service 将 duplicate-key 异常转换成注册的业务异常。
- 知识库创建固定进入 `AVAILABLE`。删除公开的通用名称驱动状态 setter；保留 `AVAILABLE`、`DELETING` 和 `DELETE_FAILED` 三个状态，后两个状态的写入命令留给后续删除需求。
- 知识管理员完整列表与普通用户可用列表是不分页的独立查询。普通用户查询的状态由 Service 指定，Mapper 在 MySQL 中过滤；两种列表都按更新时间降序、ID 降序稳定排序。
- 知识库表增加创建时间和自动推进的更新时间。名称、描述或状态更新都会改变更新时间；Issue 01 不把时间字段加入公开 Response。应用 schema 前必须重新确认目标表不存在；若已经执行旧 schema，则追加不可变增量脚本而不是改写已应用脚本。
- `KnowledgeBaseStatus` 和 `AnswerRecordStatus` 都包含不可变中文描述。数据库按枚举名称存储，描述不参与业务判断或持久化，Issue 01 不新增 HTTP 状态描述字段；未知数据库枚举值必须失败。
- 有领域、协议、权限或配置语义的固定值按 owner 使用枚举、常量类、错误码或 configuration properties，不散落魔法值。单次且含义明确的文案、日志模板、HTTP 路径、XML 表列名、局部数字和测试样例不机械抽取。
- 现有最小白名单 `ManagementAudit` port 与 adapter 保留。本规格不增加或承诺审计事务顺序、可靠投递、重试、补偿、outbox 或审计专用事务设施。
- 发布单元级业务异常、错误码和全局异常处理归属平台错误能力。业务失败继续返回 HTTP 200 和注册业务错误；数据库与外部 adapter 异常在 Service 边界转换，未预期异常只返回安全文案。
- Java 继续拥有 MySQL 中的全部业务状态；Python 不访问 MySQL。浏览器只经 Gateway 访问 Java，Java 分别协作 MySQL 与 Python RAG。

## Testing Decisions

- 测试以外部行为和状态为主，不断言 Controller 到 Mapper 的调用顺序、XML 内部结构、Spring 私有装配细节或 adapter 内部调用顺序。
- 最高验收 seam 保持浏览器经 Gateway 到 Java 的真实 HTTP/SSE interface；Java 在该验收中分别访问真实 MySQL 与 Python RAG。该验收证明匿名拒绝、角色授权、知识库创建与修改、可用列表、会话绑定以及空知识库 `started`/`refused` 流。
- 保留并更新现有 Controller 授权测试，证明匿名用户、普通用户和知识管理员的公开 HTTP 权限行为没有因包移动或注入方式改变。
- Service 行为测试通过 fake 或 mock Mapper 与外部 port 验证业务结果、业务异常、跨能力 seam、名称规范化、可用性检查和问答记录裁决；不为测试增加公开业务方法。
- 新增真实 MySQL MyBatis 集成 seam，并复用生产 schema SQL。它至少证明 XML 能被发现并与 interface 绑定、显式 result map 正确、自增主键能回填、枚举按名称读写、未知值失败以及时间字段排序正确。
- 知识库 MySQL 测试覆盖忽略大小写的预检冲突、数据库唯一约束兜底、创建和修改后的更新时间，以及管理/可用列表的 `updated_at DESC, id DESC` 稳定顺序。
- 会话 MySQL 测试覆盖插入主键、用户与知识库字段映射、按 ID 查询及不存在结果。
- 问答记录 MySQL 测试覆盖创建为 `STARTED`、条件迁移到 `REFUSED`/`UNFINISHED`、同终态重复幂等、不同迟到终态不覆盖以及记录缺失裁决。
- 保留 Java/Python 根 schema、样例以及 Java HTTP/SSE consumer/provider 契约测试。Java wire model 必须映射根契约，不能用复制的字面量证明兼容。
- 保留 Python RAG 测试和前端生产构建，因为 Java 包结构和公开数据形状虽然目标上不变，但最终 Issue 01 验收证据必须来自重构后的工作树。
- 重跑受影响 KB Maven 模块测试和打包；完成后重跑完整后端 Maven suite。所有重构前的 Java 测试、打包和 full-suite 结果都视为过期证据。
- 数据库 schema/Nacos 配置和真实环境写入仍需用户单独授权。未实际运行的 MySQL 集成、浏览器验证或系统验收不得报告为通过。
- 最终提交前以固定点对完整改动运行 Standards 与 Spec 双轴 code review，修复 hard findings 后才能更新 Issue 01 状态。

## Out of Scope

- 企业文档上传、MinIO、解析、嵌入、Milvus 发布和清理。
- 反馈能力及其界面、正文授权和处理状态。
- 知识库删除命令、删除状态迁移、级联清理和恢复。
- 完整问答、内容增量、主动停止、取消确认和会话历史等后续流状态。
- 可用知识库或管理列表分页、PageHelper、RuoYi TableDataInfo 和前端远程分页选择器。
- MyBatis-Plus、JPA、Flyway、通用 Repository、通用 persistence adapter、通用 converter 或代码生成。
- 审计可靠投递、审计事务顺序、outbox、重试、补偿和审计失败恢复。
- 新的公共常量模块、shared/common/model/ports 根包或未来能力空目录。
- 改造 RuoYi System 或公共 Mapper 扫描规则。
- 修改 Java/Python wire contract 的事件集合、字段或签名语义。
- 将状态描述、知识库时间字段或持久化 Entity 暴露到现有 HTTP Response。

## Further Notes

本规格从已完成的 grilling 决策综合而来，并补充 Issue 01 的实现与验收约束。它不替代通用企业文档问答 V1 规格，也不改变 Issue 01 的产品验收 checklist；其作用是定义 Issue 01 Java walking skeleton 的最终模块形状和持久化实现。

当前工作树包含未提交的 Java、Python、契约、前端、系统测试和文档改动。实施者必须保留无关的未跟踪内容，避免把结构重构扩展到未确认的能力，并在修改 schema 或真实环境配置前重新核对当前状态与授权。
