# 11 — 守住角色、审计与安全日志边界

**What to build:** RuoYi 的现有账号、角色、菜单和操作日志能够完整约束问答与管理行为，同时提供跨 Java、Python、存储和模型的可诊断日志，而不扩散用户或企业文档正文。

**Blocked by:** 04 — 实现批量上传与同名原子校验; 08 — 建立用户反馈隐私闭环; 09 — 实现知识库级联删除; 10 — 接入百炼 qwen-plus 生成适配器.

**Status:** ready-for-agent

- [ ] 所有已登录用户默认拥有普通问答能力，匿名请求被拒绝；只有知识管理员角色可访问知识库、企业文档和反馈管理页面/API。
- [ ] 所有知识管理员可管理全部知识库、企业文档和反馈，不引入知识库所有者或协作者；知识管理员同时保留普通问答能力。
- [ ] 普通用户只能读取和删除自己的会话与问答，不能浏览企业文档；知识管理员也不能绕过反馈同意读取未提交正文。
- [ ] 知识库、企业文档和反馈管理操作记录操作者、对象、时间和结果，并可从 RuoYi 现有审计入口核验。
- [ ] 请求/任务 ID 贯穿 Java、Python、MinIO、Milvus 和模型调用；日志记录实例、阶段、文档/片段 ID、分数、耗时、模型请求 ID、SSE 进度、重试和错误栈。
- [ ] 自动化隐私测试证明技术日志不含问题正文、片段正文、完整提示词、完整或残缺回答，也不记录百炼凭据。

## Comments

### 2026-07-31 Split into tracer-bullet tickets

本工单打包了三个相互独立、验证入口不同的 seam，已拆分为以下子工单以保持每个实现窗口聚焦：

- [11a — 角色与访问权限边界（RBAC）](11a-roles-access-control.md)
- [11b — 管理操作审计记录](11b-management-operation-audit.md)
- [11c — 跨语言诊断日志关联与隐私安全日志](11c-cross-language-diagnostics-and-privacy-logging.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.
