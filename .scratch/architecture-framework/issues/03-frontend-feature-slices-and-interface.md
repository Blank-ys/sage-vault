# 确定前端业务切片与后端 interface 落点

Type: grilling
Status: resolved
Blocked by: none

## Question

`frontend/src` 中的问答工作台、会话历史、知识库管理、企业文档管理和反馈处理应如何按业务能力组织，并如何复用现有 RuoYi 的路由、权限、状态、请求与通用 UI？请确定视图、业务状态、后端 interface 适配器和可复用 UI 的依赖方向，避免按页面复制逻辑或把 Sage Vault 业务塞进通用 `utils`、`components` 和全局 store。

## Answer

### 一级结构与业务切片

- Sage Vault 新前端代码采用 feature-first 结构，进入 `frontend/src/features/<feature>/`；现有 RuoYi 的 `views/system`、`api/system`、全局 store、布局和通用机制保持原状，不为统一外观而重构。
- V1 固定四个业务切片：`conversations` 同时拥有问答工作台、会话历史、问答记录和流生命周期；`knowledge-bases` 同时拥有普通用户的知识库选择与知识管理员的知识库管理；`enterprise-documents` 拥有企业文档列表、批量上传、状态、重试和删除；`feedback` 拥有普通用户提交反馈的授权交互与知识管理员的处理队列。
- 每个 feature 可就近拥有 `pages/`、composables 或 feature store、后端 adapter、DTO 映射、专用 UI 和公开入口。页面只是所属 feature interface 的调用者，不直接拼装请求、解析 wire event 或读取其他 feature 的内部状态。
- 跨 feature 只能从对方公开入口导入窄 interface。例如 `conversations` 可使用 `knowledge-bases` 提供的只读选择 interface，但不得导入其私有 store、adapter 或 UI。不得建立可容纳任意 Sage Vault 代码的 `shared` 或 `common` 业务目录。

### RuoYi 平台复用与动态路由

- RuoYi 继续拥有登录用户、动态菜单、路由与按钮权限、标签页、全局设置、请求拦截、通用布局和全局通知。Sage Vault feature 复用这些平台能力，不复制认证、权限路由或全局错误机制。
- 扩展现有动态路由加载器，使其在保留 `views/**/*.vue` 的同时扫描 `features/**/pages/*.vue`。后端菜单记录直接引用稳定的 feature 页面路径；不另建一套前端路由注册表，也不在前端重复菜单权限事实。
- 根 `AGENTS.md` 只作为规则地图并链接 `.agents/rules/frontend.md`；前端目录准入、依赖方向和测试约定写入该端规则文件，不把各端细则堆入根文件。

### 状态所有权

- 默认使用页面或 composable 局部状态；只有存在真实的跨页面生命周期时才提升为 feature 内 Pinia store。后端返回状态始终权威，Pinia 不建立第二套长期事实，也不把 Sage Vault 业务缓存持久化到浏览器存储。
- `conversations` 的 feature store 拥有当前会话、会话列表、问答记录和单个活跃回答流的前端生命周期。`knowledge-bases` 只有在选择器与管理页面确需共享缓存时才使用 feature store；`enterprise-documents` 和 `feedback` 默认保持局部状态。
- RuoYi 全局 store 不得新增知识库、企业文档、会话、问答记录或反馈等业务状态。跨 feature 协作通过公开 interface 和稳定标识完成，而不是共享可变 store。

### Java interface adapters 与流式语义

- 浏览器只访问 Java。普通 HTTP、分页、上传和命令由所属 feature 的后端 adapter 封装；adapter 可复用现有 `utils/request` 的认证与通用错误处理，但 DTO 映射和业务错误语义留在 feature 内。
- `conversations` 使用 `fetch` 读取 Java 返回的 SSE 流，以携带 Bearer token、提交问题并通过 `AbortController` 管理连接。页面只消费项目自有流事件，不解析原始 SSE frame。
- 用户“停止生成”必须调用 Java 的显式取消 interface，不能仅以浏览器断开连接推断。Java 持久化“已停止”并向 Python 转发取消；意外断流或取消未确认则按后端的“未完成”恢复语义处理。`AbortController` 负责释放前端连接，但不替代业务取消命令。
- `enterprise-documents` 只通过 Java 执行批量上传、重试和删除；`feedback` 只通过 Java 执行正文共享授权和处理状态变更。前端不得根据请求过程自行推导权威业务状态。

### UI 准入与测试 seam

- feature 专用 UI 与业务逻辑就近存放。只有领域无关、已经有至少两个真实消费者且 interface 稳定的展示模块才可提升到全局 `components/`；全局 UI 不读取 feature store。Sage Vault 业务逻辑不得进入 `utils/`、全局 plugins 或布局模块。
- 最高前端验证 seam 仍是浏览器到 Java 的真实 HTTP/SSE interface。系统验收覆盖动态菜单与权限、四个 feature 的关键用户路径、显式取消与意外断流的区别，以及反馈正文的授权可见性。
- 少量前端测试可经过 feature 的公开 composable/store interface 和后端 adapter seam，使用受控 HTTP/SSE 替身验证状态转换、DTO 映射和流事件归约；不得断言组件私有方法、Pinia 内部实现或页面与 adapter 的调用顺序。
