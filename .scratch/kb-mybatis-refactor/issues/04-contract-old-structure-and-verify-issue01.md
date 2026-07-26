# 04 — 收缩旧结构并验证 Issue 01 最终 Java seam

**What to build:** 在三个能力已经迁移后收缩旧的 JDBC 与横向包结构，最终确认每个有 HTTP 入口的能力都遵循能力内 `XxxController -> XxxService -> XxxServiceImpl -> XxxMapper` 主链，无独立 Controller 的问答记录能力遵循 `QaRecordService -> QaRecordServiceImpl -> QaRecordMapper`，并用完整工作树重新证明 Issue 01 行为。

**Blocked by:** 03 — 迁移会话与空知识库回答编排.

**Status:** resolved

- [x] 删除旧 application、Repository port、JDBC persistence adapter、手工业务 Service 装配和无 owner 的根级 model/transport 包；不存在生产代码 `JdbcTemplate` 或旧接口调用残留。
- [x] 知识库与会话分别形成能力内 Controller-Service-Mapper 主链，问答记录形成 Service-Mapper 主链；Controller 统一命名为 `XxxController`，Service interface 统一命名为 `XxxService`，实现统一命名为 `XxxServiceImpl`，Mapper 统一命名为 `XxxMapper`。
- [x] Controller 只依赖本能力 Service interface；ServiceImpl 直接依赖本能力 Mapper，跨能力只依赖对方 Service interface，不存在 Controller 到 Mapper、Controller 到 ServiceImpl 或跨能力 Mapper 依赖。
- [x] 不保留 `XxxApplication`、无 owner 的 `AnswerService` 或其他旧式公开接口/实现命名；没有真实 HTTP 入口的能力不为命名对称创建空 Controller。
- [x] 最终目录只包含 Issue 01 已实现的知识库、会话和问答记录能力及必要 platform/bootstrap 资产，不预建企业文档、反馈或其他未来能力空壳。
- [x] Service 和 adapter 使用 Spring stereotype 与构造器注入，Mapper 只由模块私有扫描配置注册；bootstrap 不手工构造业务 Service 或唯一 adapter。
- [x] Request/Response、Entity、领域模型、外部 port 和 Java/Python wire model 都停留在已确认 owner 边界，跨能力依赖不越过公开 Service。
- [x] 模块不再直接依赖 JDBC starter，并继续继承父 POM 锁定的 MyBatis 版本；有效依赖中不存在 MyBatis-Plus 或意外版本覆盖。
- [x] 重跑所有受影响 Java Service、HTTP 授权、RAG consumer 和真实 MySQL Mapper 测试，并成功完成 KB 模块打包。
- [ ] 重跑 Java/Python 根契约检查及 Java consumer/provider 测试，证明 adapter 读取权威契约而非复制未注册语义。
- [x] 重跑完整后端 Maven suite；所有重构前的 Java 测试、打包和 full-suite 结果均视为过期证据。
- [ ] 在具备单独授权和真实环境时，从浏览器/Gateway 到 Java 验证角色、知识库、会话和空知识库拒答；Java 分别访问 MySQL 与 Python，Python 不访问 MySQL。无法执行时准确记录剩余风险，不把未运行写成通过。
- [x] 使用固定点完成 Standards 与 Spec 双轴 code review 并修复 hard findings；本票完成后回到父 Issue 01 更新证据，不能由本票直接关闭父 Issue。

## Answer

- Removed the manual `WebClient.Builder` configuration and injected Spring Boot's builder directly into the conversation RAG adapter. Moved the Sage Vault business-exception HTTP advice into the owned `platform/error` package; MVC coverage proves a controller business error returns HTTP 200 plus the registered code and message.
- Production sources contain no `JdbcTemplate`, repository port, legacy application service, or ownerless transport package. `JdbcTemplate` remains only in real-MySQL mapper tests for fixture setup, direct database assertions, and cleanup; production persistence is exclusively MyBatis Mapper XML.
- Passed with `F:\environment\maven-repository` and the supplied MySQL: focused affected suite (15 tests), subsequent `KnowledgeBaseAuthorizationTest` (4 tests), final full `ruoyi-kb-management` reactor suite (25 tests), and `-DskipTests package`. Root contract schema/example check passed with `python -m unittest contracts.tests.test_examples -v`.
- Python consumer/provider contract test was not run: the available interpreter lacks `fastapi`, and `uv` execution is denied by the host. Browser/Gateway acceptance was not run: `SAGE_VAULT_GATEWAY_URL`, administrator token, and general-user token were not supplied. These are remaining environment-validation risks; this ticket does not close parent Issue 01.
- Standards and Spec review completed against the staged Issue 04 change. The only hard finding was missing HTTP advice coverage, fixed by `KnowledgeBaseAuthorizationTest`.
