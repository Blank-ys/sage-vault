# 01 — 迁移知识库能力到 MyBatis

**What to build:** 将知识库能力重构为能力内 `KnowledgeBaseController -> KnowledgeBaseService -> KnowledgeBaseServiceImpl -> KnowledgeBaseMapper` 主链。在不改变 Issue 01 公开行为的前提下，让知识管理员继续创建、查看和修改知识库，让普通用户获得按最近更新时间倒序排列的完整可用知识库列表，并由 MyBatis 与真实 MySQL 保证名称唯一性、持久化映射和稳定排序。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

## Answer

Implemented the knowledge-base MyBatis migration and verified it against the rebuilt production schema.

- `KnowledgeBaseController -> KnowledgeBaseService -> KnowledgeBaseServiceImpl -> KnowledgeBaseMapper`
- XML mapping, generated IDs, enum persistence, unique-key conflict mapping, `updated_at` ordering, and AVAILABLE filtering
- `mvn -f backend/pom.xml -pl ruoyi-kb-management -am test` passed: 14 tests, including real MySQL coverage

- [ ] 知识管理员仍可创建、查看和修改知识库；创建固定进入可用状态，业务失败继续返回已注册的业务错误。
- [ ] 知识库代码按能力内 `controller`、`service`、`service/impl`、`domain`、`mapper` 和必要的 `service/port`、`adapter` 组织；不得改成发布单元根级的横向技术包。
- [ ] `KnowledgeBaseController` 只注入 `KnowledgeBaseService` interface，业务实现命名为 `KnowledgeBaseServiceImpl`，实现直接调用本能力 `KnowledgeBaseMapper`；Controller 不依赖 ServiceImpl、Mapper 或 Entity。
- [ ] 知识库名称忽略首尾空白和大小写后全局唯一；Service 预检提供明确反馈，MySQL 唯一约束裁决并发冲突。
- [ ] 知识管理员完整列表与普通用户可用列表使用独立查询，均按更新时间降序、ID 降序稳定排列；普通用户列表只包含 Service 指定的可用状态且不分页。
- [ ] 知识库持久化使用独立 Entity、MyBatis Mapper interface 与 XML SQL，并正确回填自增主键；Request/Response 不进入 Mapper。
- [ ] 模块显式使用父 POM 管理的 MyBatis starter，统一注册 Mapper 并显式发现 XML；不引入 MyBatis-Plus、不重复声明版本、不扩大 RuoYi 公共 Mapper 扫描。
- [ ] Service 与现有最小白名单审计 adapter 通过 Spring 构造器注入；不增加审计事务顺序、可靠投递、重试或补偿语义。
- [ ] 删除公开的通用知识库状态 setter，保留窄的按 ID 可用性检查；不可用状态由测试 fixture 安排，不为测试扩大公开 Service。
- [ ] 真实 MySQL 测试复用生产 schema，证明 XML 绑定、显式映射、枚举名称读写、自增主键、唯一键兜底、时间更新和列表排序。

## Comments

### 2026-07-26 reopened for completion

This ticket was previously marked resolved before the full Issue 01 refactor and verification were complete. The implementation also added explicit transactions around single-SQL knowledge-base writes and an after-commit audit ordering guarantee, both outside this ticket's accepted scope. Complete the remaining checks before resolving it again: keep `KnowledgeBaseName` as the single owner of trim/case normalization, retain the minimal direct `ManagementAudit` call, and rerun the scoped Service, authorization, and real MySQL tests.
