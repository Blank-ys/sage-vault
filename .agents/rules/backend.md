---
paths:
  - "backend/**/*.java"
  - "backend/**/*.xml"
  - "backend/**/*.yml"
  - "backend/**/*.yaml"
  - "backend/**/pom.xml"
---

# Java 后端规则

处理 `backend/` 前先读根 `AGENTS.md`、`docs/architecture.md` 和 `docs/code-framework.md`。本文件只补充 Java 端编码和验证规则；目录树与系统所有权以两份架构文档为准。

## 模块与依赖

- Sage Vault 知识库管理是后端父工程直接聚合的单一发布单元，不并入 `ruoyi-system`，也不拆成多个微服务。
- 以业务能力组织代码。Controller 只调用本能力公开 application interface；跨能力也只导入对方公开 application interface。
- application 实现负责编排流程，通过 Mapper 或 adapter 产生副作用。禁止跨能力访问 Mapper、持久化对象、实现类或 adapter。
- RuoYi 安全、审计、MinIO 和 Python transport 位于 adapter seam 后；供应商、框架和持久化类型不得进入 application interface 或 wire contract。
- Sage Vault 业务类型和流程不得放入 `ruoyi-system`、`ruoyi-file` 或 `ruoyi-common`。只有发布单元级平台机制可以进入本单元 `platform`。

## 状态、事务与异步

- Java 是知识库、企业文档、任务、会话、问答记录、单活跃回答和反馈状态的唯一权威。
- 业务状态变更与持久化任务创建必须在同一 MySQL 本地事务提交；外部 HTTP、MinIO 和耗时处理不得放在该事务内。
- 提交后才派发异步任务。任务必须持久化，并按 `taskId`/`attempt`、租约或乐观锁支持幂等、乱序处理和多实例恢复。
- 网络超时重投沿用当前 `attempt`；只有显式业务重试才增加 `attempt`。回调由 Java 裁决，不按到达顺序直接覆盖状态。
- V1 使用模块内 scheduler 扫描待发送、超时和可重试任务；不要引入 XXL-JOB、Redis Stream、Redisson 或内存状态机。

## API、契约与数据

- 浏览器只经 Gateway/Java 访问业务能力；Java 负责认证授权、状态裁决、用户文案和 SSE 转发。
- HTTP、回调和 SSE 模型只在 transport adapter 内映射根 `contracts/java-python-rag/` 契约，不得复制 schema 或临时新增未注册语义。
- Mapper 模型不得直接作为 HTTP 响应或跨进程契约。业务表只引用稳定的 RuoYi 用户 ID，不复制用户、角色或部门数据，不建立跨服务数据库外键。
- 企业文档原文件由企业文档能力的 MinIO adapter 管理，不经过 `ruoyi-file`。Java 不直连 Milvus 实现业务流程。
- 内部 Java/Python 调用使用部署密钥签名、时间戳和重放窗口，传播 `requestId` 及相应任务或生成 ID；不得转发用户 token。

## 安全与日志

- 权限判断复用 RuoYi 安全上下文 adapter；知识管理员操作只经 `ManagementAudit` interface 发送白名单字段。
- 问题、回答、文档片段、完整提示词、预签名 URL 和凭据不得进入操作审计或技术日志。
- 禁止使用会默认序列化 Sage Vault 请求或响应正文的审计注解。
- 异常日志使用参数化消息并保留异常对象；不得吞异常或把内部诊断原样暴露给用户。

## SQL 与配置

- V1 不使用 Flyway。业务 SQL 使用人工执行、不可变的增量编号脚本；已发布脚本不得改写，只能追加。
- MySQL 集成测试复用生产业务 SQL，不维护测试专用 schema 副本。
- 本地 `bootstrap.yml` 只保存服务名、端口、profile 和 Nacos 导入规则；真实地址、密码和密钥来自 Nacos 或环境变量。

## 验证

- 运行受影响 Maven 模块测试；公共能力、父 POM 或跨模块改动运行 `mvn -f backend/pom.xml test`。
- 持久化、租约、恢复或乱序逻辑使用真实 MySQL 和同一组业务 SQL 验证。
- MinIO 行为经企业文档 application interface 使用真实 MinIO 验证。
- Java/Python 契约改动运行根 schema/样例检查和 Java consumer/provider 测试；涉及完整行为时补浏览器到 Java 的 HTTP/SSE 系统验收。
