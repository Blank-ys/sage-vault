# 05 — 统一后台视觉规范与验收边界

**What to build:** 为知识业务页面、系统管理和系统监控建立统一的管理后台视觉约束，并确保两个子 ticket 可以在同一外壳下独立验收。统一侧栏、顶栏、页面背景、标题层级、面包屑、表格、分页、表单、弹窗、按钮、筛选区、加载和空状态的视觉语言；不统一各页面真实字段或业务流程。

**Blocked by:** 04 — 建立管理后台外壳与管理概览.

**Status:** resolved

- [x] 知识业务页面与系统管理/监控页面共享同一管理后台外壳和视觉变量
- [x] 少量筛选条件可与操作区紧凑排列，条件较多时保留独立筛选行并支持换行
- [x] 表格、分页、表单、弹窗、按钮、加载、空状态和错误状态有一致的可观察表现
- [x] 不改变任何页面的信息架构、接口、字段、权限和业务状态
- [x] 视觉规范可被 06 和 07 分别实现并独立验证

## Answer

建立管理后台统一视觉基线，供 06（知识业务页面）与 07（系统管理/监控页面）独立采用。所有资产为 opt-in，未应用 `.management-*` 的现有页面不受影响；信息架构、接口、字段、权限和业务状态均未改动。

### 交付物

1. `frontend/src/assets/styles/management.scss`（经 `index.scss` 引入）
   - 亮/暗双模式 CSS 变量：`--mgmt-page-bg`、`--mgmt-content-bg`、`--mgmt-content-border`、`--mgmt-title-color`、`--mgmt-subtitle-color`、`--mgmt-page-padding`、`--mgmt-section-gap`、`--mgmt-filter-gap`。暗色模式复用 `--el-bg-color*` 与 `--el-text-color*`，与现有深色模式一致。
   - 工具类：`.management-page`、`.management-page-header(_title-block)`、`.management-page-title/subtitle`、`.management-page-actions`、`.management-filters(--inline|--stacked)`、`.management-toolbar`、`.management-table`、`.management-pagination`、`.management-empty`、`.management-error`，含 768px 窄屏换行适配。
   - 筛选区两种布局：`--inline`（少量条件与操作区同排，操作区 `margin-left:auto`）与 `--stacked`（条件较多时独立成行并 `flex-wrap`），对应 checklist 第 2 项。
2. `frontend/src/components/ManagementPageHeader/index.vue`（全局注册于 `main.js`）
   - 领域无关、slot-based（`title` / `subtitle` props + `actions` slot），不读取任何 feature store，不承载业务逻辑。
   - 提供统一标题层级与间距，供 06/07 各页复用，避免重复标记。
3. `frontend/src/views/admin/index.vue` 作为参考实现
   - 改用 `.management-page` + `<management-page-header>`，移除自定义 `.overview-header/.overview-title/.overview-subtitle`，保留管理概览的菜单网格与空状态提示。验证统一外壳在管理后台首页可观察。

### 06/07 落地方式

- 知识业务页面（06）：将 `features/{knowledge-bases,enterprise-documents,feedback}/pages/ManagementPage.vue` 根节点替换为 `.management-page`，标题区改用 `<management-page-header>`，筛选区按条件数量选择 `--inline`/`--stacked`，分页改用 `.management-pagination`。
- 系统页面（07）：`views/system/**` 与 `views/monitor/**` 在保留 `el-form :inline`、`right-toolbar`、`pagination`、按钮权限的前提下，套用 `.management-page` + `<management-page-header>` + `.management-filters--stacked` + `.management-toolbar`，不改变现有查询字段、增删改导出与接口。

### 验证

- `yarn --cwd frontend build:prod` 通过（exit 0）。
- 侧栏、顶栏、面包屑、深色侧栏与无可见 TagsView 的外壳由 04 已建立，本次未改动。
- 未改任何 Java/Python 接口、SSE、菜单、权限标识或业务状态。
- 浏览器视觉验收（桌面 1440x900 / 移动 390x844、亮+暗、空列表/加载/错误/弹窗）留给 06、07 各自落地后在真实管理账号下完成；本工单仅提供可被独立验证的共享基线。
