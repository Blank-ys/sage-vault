# 03 — 迁移会话与空知识库回答编排

**What to build:** 将会话能力重构为能力内 `ConversationController -> ConversationService -> ConversationServiceImpl -> ConversationMapper` 主链。让普通用户继续创建绑定知识库的会话并发起问题，由 Java 校验会话归属和知识库可用性、在数据库事务之外调用 Python RAG，并通过问答记录能力持久化空知识库拒答或流错误结果。

**Blocked by:** 01 — 迁移知识库能力到 MyBatis; 02 — 迁移问答记录状态裁决到 MyBatis.

**Status:** resolved

- [ ] 单一 `ConversationService` 公开创建会话和发起问题；Controller 不再依赖独立回答 Service，也不直接访问其他能力。
- [ ] 会话代码按能力内 `controller`、`service`、`service/impl`、`domain`、`mapper`、`service/port` 和 `adapter` 组织；不得恢复发布单元根级横向 `controller/service/mapper` 包。
- [ ] `ConversationController` 只注入 `ConversationService` interface，业务实现命名为 `ConversationServiceImpl`，实现直接调用本能力 `ConversationMapper`；Controller 不依赖 ServiceImpl、Mapper、Entity 或 RAG adapter。
- [ ] 会话创建使用 MyBatis Mapper 与独立 Entity，正确回填主键，并在创建前通过知识库能力的窄接口确认知识库可用。
- [ ] 发起问题通过会话 Mapper 校验会话存在和用户归属，不进行 Service 自调用；无权访问和不存在继续返回明确业务错误。
- [ ] 发起问题再次通过知识库 Service 检查可用性，并通过问答记录 Service 创建和裁决记录；不得跨能力访问 Mapper、Entity 或 ServiceImpl。
- [ ] RAG port 位于会话能力，adapter 使用 Spring 自动注入并继续通过 Nacos 发现 Python、签名内部请求和转换 SSE 事件。
- [ ] Java/Python 请求、响应和 SSE wire model 保持在 RAG adapter 内；Java 项目自有回答事件归属会话 domain，问答记录能力不依赖流事件类型。
- [ ] 流式发问不持有覆盖 RAG HTTP/SSE 生命周期的数据库事务；RAG 错误通过问答记录短事务裁决为未完成，连接断开不等于主动取消。
- [ ] 现有空知识库 HTTP/SSE consumer 测试继续证明 `started` 与 `refused` 行为、签名和服务发现；会话持久化由真实 MySQL 测试证明字段映射和不存在结果。

## Answer

Implemented the conversation MyBatis migration and answer orchestration.

- `ConversationController -> ConversationService -> ConversationServiceImpl -> ConversationMapper`
- Conversation creation and question orchestration now use the single conversation service; ownership is checked through its mapper, knowledge-base availability through `KnowledgeBaseService`, and QA state changes through `QaRecordService`.
- The conversation-owned RAG port and discovered signed SSE adapter now live in the conversation capability; the former root-level JDBC conversation and independent answer-service chain are removed.
- Verified with `MAVEN_OPTS=-Dmaven.repo.local=F:\environment\maven-repository` and the configured MySQL test database: `mvn -f backend/pom.xml -pl ruoyi-kb-management -am test` (21 tests passed, including real MySQL mapper tests).
