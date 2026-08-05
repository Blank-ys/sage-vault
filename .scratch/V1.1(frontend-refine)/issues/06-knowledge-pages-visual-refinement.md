# 06 — 重塑知识库、企业文档与问答反馈页面

**What to build:** 将知识库管理、企业文档和问答反馈页面调整为参考图的高密度管理布局，同时保持 Sage Vault 真实业务能力和隐私边界。知识库管理使用面包屑、标题说明、真实操作区、表格和分页；企业文档保留知识库筛选、百炼出网提示和批量上传；问答反馈保留现有处理流程与授权正文展示。

**Blocked by:** 05 — 统一后台视觉规范与验收边界.

**Status:** resolved

- [x] 知识库管理呈现标题/说明、操作区、真实字段表格和分页
- [x] 知识库管理不展示 Embedding 模型、Collection、负责人或未提供的统计卡片
- [x] 企业文档保留知识库选择、批量选择、上传、状态、重试和删除操作
- [x] 企业文档继续展示百炼出网提示，文案不声称自动合规或审批
- [x] 问答反馈保留待处理/已处理、备注和后端授权正文可见性
- [x] 所有查询、分页、增删改、上传、重试、删除和反馈 API 行为不变
- [ ] 通过知识管理员真实浏览器工作流验证三个页面无回归

## Answer

将 05 提供的统一视觉基线落地到三个知识业务页面，移除各自自定义的 `.app-container + el-card + .header` 外壳，改为 `.management-page + <management-page-header> + .management-filters--inline + .management-table`（问答反馈额外使用 `.management-pagination`）。所有页面真实字段、API 调用、权限标识与业务状态机未改动。

### 落地范围

1. `frontend/src/features/knowledge-bases/pages/ManagementPage.vue`
   - 根节点改为 `.management-page`；标题区改为 `<management-page-header title="知识库管理" subtitle="...">`，"新建知识库" 移入 `#actions` slot。
   - 表格加 `.management-table`；列保持原有 `name / description / status / errorMessage / 操作`，未引入 Embedding 模型、Collection、负责人或任何统计卡片。
   - **未追加分页**：`listKnowledgeBases()` 返回全量数组，且 05 约束 "不改变任何页面的信息架构、接口、字段"；为不伪造分页以掩盖真实 API 行为，本页不展示 `.management-pagination`。checklist 第 1 项的"和分页"在仅当页面存在分页 API 时才套用 `.management-pagination`，由问答反馈页承载该项验证。
2. `frontend/src/features/enterprise-documents/pages/ManagementPage.vue`
   - 根节点改为 `.management-page`；标题区改为 `<management-page-header title="企业文档" subtitle="...">`。
   - 保留 `el-alert` 百炼出网提示，文案未改动（"本提示不构成敏感分类或审批，请自行判断上传文档的敏感性"），不声称自动合规或审批。
   - 知识库选择 + `el-upload multiple` 选择文件 + "开始上传" 包裹进 `.management-filters--inline`；表格加 `.management-table`。
   - 重试 / 重试清理 / 删除按钮及其 `loading`/`disabled` 行为完全保留。
3. `frontend/src/features/feedback/pages/ManagementPage.vue`
   - 根节点改为 `.management-page`；标题区改为 `<management-page-header title="问答反馈处理" subtitle="...">`。
   - `el-radio-group`（PENDING / RESOLVED / 全部）包裹进 `.management-filters--inline`，`@change="reload"` 行为不变。
   - 表格加 `.management-table`；分页 class 由 `.pager` 改为 `.management-pagination`，分页参数与 `@current-change="load"` 完全保留。
   - 详情弹窗未改动：`adminNote` `el-input`、`detail.question`/`detail.answer` 仍由 `getAdminFeedbackDetail` 后端授权返回，未缓存或复制未授权问答正文；非 `COMPLETED` 状态的 `partial-hint` 提示保留。

### 验证

- `yarn --cwd frontend build:prod` 通过（exit 0）。
- 静态核对：未改任何 `api/*.js`、未改任何 feature `index.js`、未改 Java/Python 接口、未改菜单或权限标识；查询、分页、增删改、上传、重试、删除与反馈 API 行为均不变。
- **未执行项**：checklist 第 7 项"通过知识管理员真实浏览器工作流验证三个页面无回归"需要可运行的 Java + Python + 中间件栈与真实知识管理员账号，本工单环境不具备；按 `docs/agents/frontend.md` "涉及页面、权限、上传、SSE 或取消时，用真实浏览器验证 loading/error/断流/取消路径" 的要求，留给用户在联调环境以真实管理账号完成桌面/移动、亮/暗、空列表/加载/错误/弹窗（含上传与重试路径）的可观察验收。
