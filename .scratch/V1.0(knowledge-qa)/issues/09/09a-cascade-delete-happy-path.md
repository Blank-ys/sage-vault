# 09a — 知识库级联删除 happy path：关闭闸门、后台清理、历史标记

**What to build:** 知识管理员二次确认后删除整个知识库，系统立即进入删除中并拒绝新的上传、会话和问答，后台级联清理其内所有企业文档、MinIO 对象、解析产物和 Milvus 向量，全部成功后删除知识库活动记录；已有会话与问答记录继续可读并标记"知识库已删除"但不能继续提问，删除知识库不删除历史反馈。

**Blocked by:** 06 — 完成文档删除与名称释放; 07 — 完善会话、历史与流式中断.

**Status:** resolved

- [x] 删除知识库要求二次确认；提交后通过 CAS 立即进入删除中，并拒绝新的上传、会话和问答。
- [x] 后台级联清理知识库内所有企业文档、MinIO 原文件、解析产物和 Milvus 向量。
- [x] 全部清理成功后删除知识库活动记录。
- [x] 已有会话与问答记录继续可读并标记"知识库已删除"，但不能继续提问。
- [x] 删除知识库不删除历史反馈。
- [x] 前端提供二次确认弹窗与"删除中"标签。
- [x] 验证：系统验收覆盖 删除→立即拒绝新操作→级联清理成功→活动记录移除→历史可读不可问。

## 实现落点

- `sql/010_schema.sql`：解除 `sv_conversation` 对 `sv_knowledge_base` 的外键（知识库活动记录删除后历史必须仍可读），补 `error_message` / `cleanup_attempt` 与状态索引。
- `KnowledgeBaseService#delete`：CAS `AVAILABLE|DELETE_FAILED → DELETING`，重复删除幂等；`DELETE /knowledge-bases/{id}` 走 `sage:knowledge-base:manage` 权限。
- `requireAvailable`：知识库缺失时抛 `KNOWLEDGE_BASE_DELETED`。上传、建会话、提问共用这一道闸门，所以删除返回即已关闭全部新写入。
- `KnowledgeBaseContentCleaner`（知识库侧窄 port）+ `DocumentKnowledgeBaseContentCleaner`（文档侧实现）：知识库不感知文档表、MinIO 与 Milvus，清理复用 issue 06 的单文档清理链路。
- `KnowledgeBaseCascadeDeleteTask`：每 5s 推进一轮，只有内容确认清空才 `deleteByIdIfDeleting`；失败转 `DELETE_FAILED` 并保留残留与诊断信息，不伪装成功。
- `ConversationResponse` 增加 `knowledgeBaseDeleted` / `knowledgeBaseName`；前端会话列表打"知识库已删除"标签、顶部提示只读、禁用提问输入。
- 前端 `ManagementPage.vue`：二次确认弹窗说明不可恢复且历史保留，"删除中"标签 + 删除中禁用编辑/删除，`DELETE_FAILED` 展示失败原因并支持重试删除。

## 验证

已执行：

- `mvn -o -pl ruoyi-kb-management test`（连真实 MySQL）：206 passed / 2 skipped（skip 项需 MinIO、Milvus 环境变量）。
- `KnowledgeBaseMapperMySqlIntegrationTest` 对 `192.168.150.100:3306/ry-cloud` 真实执行：4 passed。覆盖 CAS 状态守卫、清理计数、`deleteByIdIfDeleting` 只在 `DELETING` 生效，以及外键解除后会话/问答/反馈在知识库删除后仍存活。
- `yarn --cwd frontend build:prod`：构建成功。
- `sql/010_schema.sql` 已应用到 `192.168.150.100:3306/ry-cloud`，并核对 `sv_conversation → sv_knowledge_base` 外键已移除。

未执行（阻塞原因）：

- 原阻塞：网关 `192.168.150.100:8899` 运行旧版构建，无法验证。已于 2026-08-02 后端重启后解除。

已执行（系统验收）：

- `system-tests/knowledge-qa/test_cascade_delete_knowledge_base.py` 已对真实运行环境执行：2 passed / 0 failed（32.9s）。
  环境：`SAGE_VAULT_GATEWAY_URL=http://192.168.150.100:8899`，管理员 token（admin）+ 普通用户 token（blank）注入运行。
  覆盖：删除返回即进入 DELETING 并关闭新上传/会话/问答闸门；空知识库级联删除完成且重复删除幂等；带文档知识库级联清理企业文档（MinIO 原文件 + Milvus 向量，依赖 Python RAG 已起）后活动记录被移除；历史会话与问答仍可读取并标记 `knowledgeBaseDeleted=true`，且删除后继续提问被 `KNOWLEDGE_BASE_DELETED(410030)` 拒绝。
- 修复了测试脚本与实际契约的两处偏差：文档上传路径应为 `POST /ruoyi-kb-management/documents?knowledgeBaseId=...`（原写的 `/knowledge-bases/{id}/documents` 在网关 404/500）；删除后 `GET /knowledge-bases/{id}` 返回 `KNOWLEDGE_BASE_NOT_AVAILABLE(410002)`（记录已物理移除），原断言错用不存在的 `410001`。

剩余风险：

- 浏览器端真实页面交互（确认弹窗、删除中标签、历史只读提示）仍仅通过 `yarn build:prod` 构建通过验证，未做真人浏览器点击验证。后端对外契约已通过系统测试确认。
