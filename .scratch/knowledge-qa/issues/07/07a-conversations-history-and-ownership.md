# 07a — 会话组织、永久历史与所有权级联删除

**What to build:** 普通用户能新建绑定单个知识库的会话，用首个问题作为默认可改标题，并在会话内保存多条按时间展示的独立问答记录；历史消息不参与后续检索、问题改写或模型上下文；用户只能查看和删除自己的会话与问答，删除会话级联删除其中问答与反馈正文，但保留不含正文的操作审计。

**Blocked by:** 02 — 上传并问答一篇 TXT 企业文档（已 resolved）.

**Status:** resolved

- [x] 新增会话表（含 id、user_id、kb_id、title、created_at、updated_at）与问答记录表（含 id、conversation_id、question、answer、status、created_at）。
- [x] 会话 CRUD API：新建会话绑定单个知识库；首个问题成为默认标题；标题可修改。
- [x] 一个会话可保存多条按时间展示的独立问答记录。
- [x] 每个问题始终只检索会话绑定的知识库；历史消息不参与检索、问题改写或模型上下文（无状态单轮检索）。
- [x] 所有权隔离：用户只能查看和删除自己的会话与问答记录，越权访问返回明确业务错误。
- [x] 删除会话级联删除其中问答与反馈正文，但保留不含正文的操作审计事实。
- [x] 前端提供会话列表、切换、标题编辑与历史问答查看。
- [x] 验证：受影响 Java 模块测试 + 前端 `build:prod` + 系统验收覆盖会话所有权隔离与历史不参与检索。

## 实现落点

- 迁移：`backend/ruoyi-kb-management/sql/007_schema.sql`
  - `sv_conversation` 补 `title`、`updated_at` 与 `(user_id, updated_at)` 索引。
  - `sv_qa_record` 外键改为 `ON DELETE CASCADE`，补 `(conversation_id, created_at)` 索引。
  - 会话表在 02 已建，问答记录表字段在既有 schema 中已满足，本单只补齐缺失列与级联。
- 后端：`com.sagevault.kb.conversation`
  - `ConversationController`：`GET /conversations`、`GET /conversations/{id}`、`GET /conversations/{id}/questions`、`PUT /conversations/{id}/title`、`DELETE /conversations/{id}`。
  - `ConversationServiceImpl`：`requireOwned` 统一做归属校验；`applyDefaultTitle` 只在首问且标题为空时写入默认标题，其余提问只推进 `updated_at`；`delete` 先级联清正文再删会话，最后写审计。
  - `ConversationAudit`（owner 侧 port）+ `RuoyiConversationAudit`（platform adapter）：只透出 `conversationId` 与被清除条数，不带任何正文。
- 后端：`com.sagevault.kb.qarecord` 增加 `listByConversation` / `hasRecords` / `deleteByConversation`。
- 前端：`frontend/src/features/conversations/` 的 `api/conversations.js` 与 `pages/WorkspacePage.vue` 提供会话列表、切换、改名、删除与历史问答查看。
- 无状态单轮检索由 `RagAnswerPort.answer(knowledgeBaseId, question, requestId, generationId)` 的签名结构性保证：端口不接收历史，历史无法进入检索或模型上下文。

## 验证证据

- `mvn -o -B -pl ruoyi-kb-management test`（本机仓库 `F:\environment\maven-repository`）：BUILD SUCCESS，Tests run: 130, Failures: 0, Errors: 0, Skipped: 21。
  - 跳过的 21 条是需要真实 MySQL 的 `*MySqlIntegrationTest`（未设置 `SAGE_VAULT_MYSQL_TEST_URL`），其中已新增 `findByUser` 归属倒序、`updateTitle`/`deleteOwned` 越权返回 0、删除会话级联清空 `sv_qa_record` 以及 `findByConversation`/`countByConversation`/`deleteByConversation` 的用例，待有库环境时执行。
  - 新增 `ConversationHistoryTest`：列表只含本人会话且按最近活跃倒序、首问成为默认标题、后续提问不覆盖标题、改名后标题不被覆盖、历史按提问顺序返回、他人读改删均被拒、删除后级联清正文且审计不含正文、空标题改名被拒、两次提问都只带知识库与本次问题进入 RAG。
  - 扩充 `ConversationAuthorizationTest`：匿名不能列会话，列表只返回本人会话，越权读/改名/删除均返回 `CONVERSATION_FORBIDDEN`。
- `yarn --cwd frontend build:prod`：成功（Done in 56.57s，产出 `WorkspacePage-*.js`）。
- 系统验收：新增 `system-tests/knowledge-qa/test_conversation_history_and_ownership.py`，覆盖默认标题、改名保留、多条历史顺序、历史不进入后续答案、第二个普通用户越权被拒与删除后不可读。**未执行**：需要真实 Gateway、Java、Python RAG、MySQL 与两个普通用户 token（`SAGE_VAULT_SECOND_USER_TOKEN`），本机无该环境。剩余风险是跨端联调行为未经真实运行证明。
