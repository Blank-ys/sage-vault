# 确定知识库管理 Java 模块的 interface 与包布局

Type: grilling
Status: resolved
Blocked by: none

## Question

与 `backend/ruoyi-modules` 同级的 `backend/ruoyi-kb-management` 应如何拥有知识库、企业文档记录、异步任务、会话、问答记录和反馈等业务能力，并通过哪些最小 interface 与 `ruoyi-gateway`、`ruoyi-auth`、`ruoyi-system`、`ruoyi-file`、MySQL 和现有审计能力协作？请确定其内部包组织、依赖方向、哪些现有模块只作为平台依赖而不得承载 Sage Vault 业务，以及对应的测试 seam。

## Answer

### 发布单元与内部组织

- `backend/ruoyi-kb-management` 是由 `backend/pom.xml` 直接聚合的单一 Maven/Spring Boot 发布单元，与 `backend/ruoyi-modules` 同级；不得放入 `ruoyi-modules`，也不得把 Sage Vault 业务并入 `ruoyi-system`。
- V1 不把知识库、企业文档、会话、问答记录和反馈拆成独立 Maven 模块或微服务。发布单元内部先按这五个业务能力分包，每个能力内部保留 RuoYi 开发者熟悉的 Controller、Service、Mapper 和 Domain 等技术角色；异步任务随发起它的业务能力就近组织，不形成可承载任意任务的公共包。
- Controller 只调用所属业务能力的 application interface；application 实现承载规则和流程，并通过 Mapper 或外部 adapter 完成副作用。Mapper 模型不得直接作为 HTTP 契约。
- 跨业务能力只能调用对方公开的 application interface，不得直接导入对方的 Mapper、持久化对象、Service 实现或 adapter。知识库能力拥有知识库级联删除用例，会话能力拥有会话及关联问答记录、反馈正文的删除用例；被协调能力只提供窄的内部清理 interface，依赖方向不得反转或成环。

### 状态与平台协作

- `ruoyi-kb-management` 是知识库、企业文档、异步任务、会话、问答记录和反馈 MySQL 表的唯一所有者。其他 RuoYi 服务和 Python RAG 服务不得直连这些表；表中只引用稳定的 RuoYi 用户 ID，不复制账号、角色或部门资料，也不建立跨服务数据库外键。
- `ruoyi-gateway` 保持统一入口，`ruoyi-auth` 保持登录职责，`ruoyi-system` 继续拥有账号、角色、菜单和权限标识。业务模块从可信安全上下文取得用户身份并校验权限标识，不维护管理员名单，也不为每次请求远程查询 `ruoyi-system`；只有确需最新用户资料时才调用既有系统 interface。
- RuoYi 中唯一的知识管理员角色是一组全局管理权限；所有知识管理员均可管理全部知识库、企业文档和反馈。普通问答能力授予所有已登录用户，不引入知识库所有者、协作者或额外的普通问答角色。
- 企业文档原文件由企业文档能力通过模块内 MinIO adapter 直接管理。`ruoyi-file` 不参与上传、读取、删除或失败补偿，也不得承载 Sage Vault 业务规则。
- Sage Vault 领域类型、状态机和流程不得进入 `ruoyi-common`。只有领域无关、至少被两个真实发布单元稳定复用、owner 明确且 interface 足够小的机制，才可进入职责明确的 `ruoyi-common-*` 模块；共享代码不得充当循环依赖中转站。

### 异步任务与恢复

- 文档入库、企业文档删除和知识库级联清理的任务记录、状态机、重试资格与恢复语义归 `ruoyi-kb-management` 所有。业务操作在同一 MySQL 本地事务中写入业务状态和持久化任务记录，提交后才派发幂等 Python 命令。
- V1 使用模块内 scheduler 扫描待发送、超时和可重试任务，并用数据库抢占、租约或乐观锁保证多实例安全。Python 回调携带任务身份和执行批次，Java 依据当前权威状态处理重复或乱序结果。
- V1 不引入 XXL-JOB。模块保留窄的恢复触发 interface；未来 XXL-JOB 或现有 `ruoyi-job` 最多作为该 interface 的 adapter，不得保存业务任务状态、直接调用 Python 或编排状态机。

### 审计与技术日志

- 知识库、企业文档和反馈等管理操作通过模块自有的 `ManagementAudit` interface 发送白名单审计字段到 `ruoyi-system` 现有操作审计能力：操作人、对象类型与 ID、动作、时间、结果和请求 ID。不得直接使用会默认序列化请求与响应的 RuoYi `@Log` 行为。
- 问答、检索、SSE 和异步任务诊断只进入结构化技术日志，不进入操作审计表。技术日志可记录关联标识、阶段、分数、耗时、模型请求标识、进度、重试和错误，但不得复制问题、回答、企业文档片段或完整提示词。
- 审计写入发生在业务结果确定之后；审计系统故障不得回滚已成功的业务事务，但必须写入可告警的技术错误。该取舍避免把远程审计纳入业务一致性，同时使审计丢失可被发现。

### 测试 seam

- 业务测试经过各能力的 application interface，观察业务结果、持久化状态和 adapter 交互，不越过 interface 断言 Controller、Service 或 Mapper 的调用顺序。
- 使用真实 MySQL 的集成测试验证本地事务、唯一约束、级联语义、任务抢占、幂等和乱序回调；MinIO、Python 与审计在不属于测试目标时使用 adapter 替身。
- 企业文档对象存储集成测试经过企业文档 application interface 使用真实 MinIO；权限验收经过 HTTP interface；恢复测试经过持久化任务与恢复触发 interface；审计测试验证白名单字段、正文禁入以及审计失败不改变业务结果。
