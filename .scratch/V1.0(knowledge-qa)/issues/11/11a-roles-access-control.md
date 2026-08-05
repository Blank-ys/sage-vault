# 11a — 角色与访问权限边界（RBAC）

**What to build:** 基于 RuoYi 现有账号、角色与菜单约束问答与管理行为：所有已登录用户默认可普通问答、匿名请求被拒绝；仅知识管理员角色可访问知识库、企业文档与反馈管理页面/API；所有知识管理员平权管理全部对象，不引入所有者或协作者；普通用户只能读写删自己的会话与问答、不能浏览企业文档；知识管理员也不能绕过反馈同意读取未提交正文。

**Blocked by:** 04 — 实现批量上传与同名原子校验; 08 — 建立用户反馈隐私闭环; 09 — 实现知识库级联删除.

**Status:** ready-for-agent

- [x] 所有已登录用户默认拥有普通问答能力；匿名/未登录请求被拒绝。（网关全局要求 token，E2E 实测未携带 token → 401）
- [x] 仅知识管理员角色可访问知识库、企业文档、反馈管理页面与对应 API；普通用户访问被拒绝。（三个管理控制器加 `@RequiresRoles("knowledge_admin")` + 既有 `@RequiresPermissions`；E2E 验证普通用户 blank/zhangsan → 403，knowledge_admin(white) → 200）
- [x] 所有知识管理员平权管理全部知识库、企业文档与反馈，不引入知识库所有者或协作者概念；知识管理员同时保留普通问答能力。（服务层按全局对象操作、无 owner 过滤；角色门禁不引入所有者语义）
- [ ] 普通用户只能读取和删除自己的会话与问答，不能浏览企业文档或他人会话。（用户侧端点由 `SecurityUtils.getUserId()` 做会话归属隔离，属 08/11b 范畴，本次未改动/未专门测试）
- [x] 知识管理员也不能绕过反馈同意读取未提交的问答正文（复用 08b 隐私隔离，不新增旁路）。（`AdminFeedbackController` 仅暴露已提交反馈；越权时 `@PreAuthorize` 切面拦截、服务层不被触达，单测 `verify(feedbacks, never())` 已证）
- [ ] 前端按角色隐藏/禁用无权入口，且后端 API 独立鉴权（不依赖前端隐藏）。（后端独立鉴权已具备；前端角色隐藏本次未改动）
- [x] 验证：受影响 Java 模块授权测试 + 系统验收覆盖 匿名拒绝 / 普通用户越权被拒 / 管理员平权访问。（`mvn test` 三个授权测试类 25 passed；网关 E2E 矩阵覆盖匿名401/普通403/管理员200）

## 改动落点

- `backend/ruoyi-kb-management/.../knowledgebase/controller/KnowledgeBaseController.java`：5 个端点加 `@RequiresRoles("knowledge_admin")`
- `backend/ruoyi-kb-management/.../document/controller/DocumentController.java`：6 个端点加 `@RequiresRoles("knowledge_admin")`
- `backend/ruoyi-kb-management/.../feedback/controller/AdminFeedbackController.java`：3 个端点加 `@RequiresRoles("knowledge_admin")`
- 对应 `*AuthorizationTest` 增强：新增"有 manage 权限但无 knowledge_admin 角色 → 403"负向用例，证明角色门禁为真

## 已知缺口（需 Nacos 配置，非本仓库代码）

- 验收标准 #6（回调免登录、纯签名校验）当前不满足：网关 `security.ignore.whites` 未包含回调路径，E2E 实测
  `POST /ruoyi-kb-management/internal/v1/indexing/callbacks` 与 `.../cleanup/callbacks` 无 token → 401。
  回调控制器本身仅做 HMAC 校验、不依赖登录态。需在 Nacos `ruoyi-gateway-dev.yml` 的 `security.ignore.whites` 增加上述两条路径。
  放开后建议再用带正确签名的请求收尾确认。
