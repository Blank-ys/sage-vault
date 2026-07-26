# 02 — 迁移问答记录状态裁决到 MyBatis

**What to build:** 将问答记录能力重构为能力内 `QaRecordService -> QaRecordServiceImpl -> QaRecordMapper` 主链。该能力在 Issue 01 没有独立 Controller，由会话能力只通过 `QaRecordService` 协作；Java 通过 MyBatis 和真实 MySQL 创建并裁决问答记录，使已接受的生成安全进入拒答或未完成终态。

**Blocked by:** 01 — 迁移知识库能力到 MyBatis.

**Status:** resolved

## Answer

Implemented the capability-local QA record MyBatis chain and bridged the existing answer orchestration through `QaRecordService`.

- Creates records directly as `STARTED`; Python `started` events are forwarded without a second database write.
- Conditional SQL only allows `STARTED -> REFUSED|UNFINISHED`; the service treats same terminal events as idempotent and distinguishes a terminal conflict from a missing record.
- Added public-service and production-schema MySQL integration tests. `mvn -f backend/pom.xml -pl ruoyi-kb-management -am test` passed with 25 tests, including real-MySQL integration coverage.

- [x] 问答记录创建时写入 `STARTED`，其含义是 Java 已接受本次生成；不新增 `PENDING` 或 `ACCEPTED`。
- [x] 问答记录代码按能力内 `service`、`service/impl`、`domain`、`mapper` 组织；公开接口命名为 `QaRecordService`，实现命名为 `QaRecordServiceImpl`，实现直接调用本能力 `QaRecordMapper`。
- [x] 问答记录能力不为目录对称创建无真实入口的 Controller；会话能力不得依赖 `QaRecordServiceImpl`、`QaRecordMapper` 或问答记录 Entity。
- [x] Python `started` SSE 事件不触发重复数据库更新，问答记录只通过明确裁决进入 `REFUSED` 或 `UNFINISHED`。
- [x] 问答记录使用独立 Entity、MyBatis Mapper interface 与 XML SQL；状态枚举按名称持久化并包含不参与持久化的描述。
- [x] 终态更新使用条件 SQL；同一终态重复事件幂等成功，迟到的不同终态不能覆盖已有终态。
- [x] Service 根据条件更新的零行结果区分幂等、终态冲突和记录缺失；Mapper 不拥有业务状态机、错误码或用户文案。
- [x] 问答记录创建和每次终态裁决由独立 Service Bean 在短事务中完成，不把事务扩展到外部 RAG 流。
- [x] 真实 MySQL 测试复用生产 schema，覆盖创建、拒答、未完成、重复终态、迟到事件、记录缺失和未知数据库枚举值。
