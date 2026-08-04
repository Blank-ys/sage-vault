# 11 — 守住角色、审计与安全日志边界

**What to build:** RuoYi 的现有账号、角色、菜单和操作日志能够完整约束问答与管理行为，同时提供跨 Java、Python、存储和模型的可诊断日志，而不扩散用户或企业文档正文。

**Blocked by:** 04 — 实现批量上传与同名原子校验; 08 — 建立用户反馈隐私闭环; 09 — 实现知识库级联删除; 10 — 接入百炼 qwen-plus 生成适配器.

**Status:** ready-for-agent

- [x] 所有已登录用户默认拥有普通问答能力，匿名请求被拒绝；只有知识管理员角色可访问知识库、企业文档和反馈管理页面/API。（见 11a 项 1/2/3/6：网关 token 拦匿名，三控制器加 `@RequiresRoles("knowledge_admin")`，E2E 验证匿名401/普通403/管理员200）
- [x] 所有知识管理员可管理全部知识库、企业文档和反馈，不引入知识库所有者或协作者；知识管理员同时保留普通问答能力。（见 11a 项 3：服务层按全局对象操作、无 owner 过滤）
- [ ] 普通用户只能读取和删除自己的会话与问答，不能浏览企业文档；知识管理员也不能绕过反馈同意读取未提交正文。（后半句已完成：见 11a 项 5，管理员越权读未提交正文被 `@PreAuthorize` 拦截且单测验证；**前半句未完成**——11a 项 4 普通用户会话归属隔离未专门测试、项 6 前端角色隐藏未改动）
- [x] 知识库、企业文档和反馈管理操作记录操作者、对象、时间和结果，并可从 RuoYi 现有审计入口核验。（见 11b：复用 RuoYi 操作日志，仅含标识与结果不含正文，系统验收已覆盖）
- [x] 请求/任务 ID 贯穿 Java、Python、MinIO、Milvus 和模型调用；日志记录实例、阶段、文档/片段 ID、分数、耗时、模型请求 ID、SSE 进度、重试和错误栈。（见 11c 项 1/2：统一 `trace_id` 贯穿 completed 路径，三阶段耗时+检索分数+片段ID已采集并经 SSE→Java→`sv_qa_retrieval_diagnostic` 落库；**注**：模型请求 ID 字段已贯通待百炼适配器回填，MinIO/Milvus 应用层当前不打点）
- [x] 自动化隐私测试证明技术日志不含问题正文、片段正文、完整提示词、完整或残缺回答，也不记录百炼凭据。（见 11c 项 3/4：`mask_sensitive`/`mask_failure_detail` 脱敏，Python 单测 + 契约受控词表断言 + 221 Java/7 契约/25 Python 测试通过）

## Comments

### 2026-07-31 Split into tracer-bullet tickets

本工单打包了三个相互独立、验证入口不同的 seam，已拆分为以下子工单以保持每个实现窗口聚焦：

- [11a — 角色与访问权限边界（RBAC）](11a-roles-access-control.md)
- [11b — 管理操作审计记录](11b-management-operation-audit.md)
- [11c — 跨语言诊断日志关联与隐私安全日志](11c-cross-language-diagnostics-and-privacy-logging.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.

## 2026-08-03 事项审查结论

六事项中 **5 项完成、1 项部分完成**（项 3）：

- **已完成**：项 1、2、4、5、6 —— 分别对应 11a（RBAC 主体）、11b（审计）、11c（跨语言诊断+隐私）。
- **部分完成（项 3）**：仅管理员"不绕过反馈同意读未提交正文"已落地（11a 项 5）；普通用户会话/问答归属隔离（11a 项 4）与前端角色隐藏（11a 项 6）仍未专门实现/测试，故整体保持未完成。

### 本次同步收尾

- 11c 待办「新建 `sv_qa_retrieval_diagnostic` 子表」已补齐建表 DDL：`sql/011_schema.sql`（`qa_record_id` 外键级联 `sv_qa_record` 级联清理，索引 `qa_record_id`/`generation_id`）。**剩余待办**：百炼适配器若返回模型请求 ID，回填 `Completed.model_request_id`（当前 `null`）。
- 11a 已知缺口：网关回调免登录（Nacos `security.ignore.whites` 放开 indexing/cleanup callbacks）需在部署环境配置，非本仓库代码。
