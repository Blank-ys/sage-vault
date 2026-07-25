---
paths:
  - "frontend/src/**/*.js"
  - "frontend/src/**/*.vue"
  - "frontend/src/**/*.scss"
  - "frontend/src/**/*.css"
  - "frontend/package.json"
  - "frontend/vite.config.js"
---

# Vue 前端规则

处理 `frontend/` 前先读根 `AGENTS.md`、`docs/architecture.md` 和 `docs/code-framework.md`。前端使用 Vue 3、JavaScript 和 ES Modules；不要引入 TypeScript、React 或 TailwindCSS。

## Feature 边界

- Sage Vault 代码按 `conversations`、`knowledge-bases`、`enterprise-documents` 和 `feedback` feature 就近组织。
- 页面只调用所属 feature 的公开 interface；跨 feature 只能从对方 `index.js` 等窄公开入口导入。
- feature 私有组件、状态、DTO 映射和业务逻辑不得进入全局 `components`、`utils`、plugins、layout 或全局 store。
- 只有领域无关、至少两个真实消费者且 interface 已稳定的展示组件才能进入全局 `components`，并且不得读取 feature store。
- 不为目录外观创建空子目录、占位 store 或预期复用的抽象。

## 状态与 API

- 后端状态始终权威；前端不得根据上传、派发或网络请求过程推导文档、任务或回答的业务终态。
- 默认使用页面或 composable 局部状态。只有真实跨页面生命周期才创建 feature store；Sage Vault 状态不得进入 RuoYi 全局 store。
- 普通 HTTP、分页、上传和命令由所属 feature 的 `api` adapter 封装；页面不得拼接 URL、重复 Axios 配置或处理原始 wire DTO。
- 浏览器只调用 Java，不得直连 Python、Milvus、MinIO 管理接口或第三方生成服务。
- 保持 RuoYi 的动态菜单、认证拦截、布局、全局通知和 Element Plus 设计语言，不另建平行基础设施。

## SSE 与取消

- 问答 SSE 使用原生 `fetch`，以携带认证头；使用 `AbortController` 管理浏览器连接，并在组件卸载时清理资源。
- 原始 SSE frame 在 `conversations` adapter 内转换为 feature 自有事件；页面和组件不得自行解析协议。
- 用户停止生成必须调用 Java 显式取消 interface；断开连接只管理 transport，不能代替业务取消。
- 区分完整、拒答、已停止和未完成；意外断流不得显示为用户主动停止。
- 同一用户只允许一个活跃回答时，由 `conversations` feature 生命周期统一约束，不能散落在按钮组件中推断。

## UI 与隐私

- 异步操作清晰呈现 loading、success、error、disabled 和可重试状态，避免用乐观文案掩盖后端未裁决状态。
- 权限控制以 Java 响应为准；隐藏按钮不是授权机制。
- 问题、回答和企业文档正文不得写入持久浏览器存储、埋点、通知详情或调试日志。
- 反馈正文的展示必须遵守后端授权结果，不缓存或复制未授权问答正文。

## 验证

- 前端改动至少运行 `yarn --cwd frontend build:prod`。
- 涉及页面、权限、上传、SSE 或取消时，用真实浏览器验证 loading/error/断流/取消路径。
- feature 状态和 DTO 映射测试断言公开 composable/store interface，不断言 Vue 私有方法或 Pinia 内部实现。
- 跨端行为优先通过浏览器到 Java 的真实 HTTP/SSE 系统验收验证。
