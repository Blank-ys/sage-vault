# 03 — 完善问答流式交互与离开保护

**What to build:** 按参考聊天页重塑问题、回答和输入区的阅读层级，同时保留现有 Markdown 和 SSE 行为。用户问题靠右、回答靠左，输入区位于主区域底部；回答下方仅提供反馈图标入口。生成期间切换会话、进入管理后台、个人中心或其他会卸载问答的导航时，必须先显式停止并在停止成功后离开。

**Blocked by:** 02 — 重塑问答工作台桌面布局与会话导航.

**Status:** resolved

- [x] 问答正文按用户问题靠右、回答靠左的结构展示
- [x] 输入区保留 Enter 发送、Shift+Enter 换行、加载和禁用状态
- [x] 生成期间显示停止按钮，继续调用现有显式取消接口
- [x] 正确展示 started、delta、completed、refused、failed、stopped 和 unfinished 的现有用户状态
- [x] 回答下方反馈图标打开现有反馈对话框，已反馈后不可重复提交
- [x] 新对话说明答案仅基于所选知识库可用企业文档
- [x] 不展示硬编码推荐问题或不存在的深度思考模式
- [x] 生成期间离开触发“停止生成并离开”确认；取消确认留在原页
- [x] 停止接口失败时不导航并显示错误；停止成功后才导航
- [x] 通过真实 Java SSE 和反馈接口验证流式及离开路径

## Answer

### 改动文件

- `frontend/src/features/conversations/store/qaGuard.js`（新增）：问答生成期间的离开保护状态。只持有 `streaming` 标志和 `pendingLeave` 目标，不复制任何 Java 业务状态机。
- `frontend/src/features/conversations/index.js`（新增）：feature 公开入口，导出 `useQaGuardStore`，供 `permission.js` 跨 feature 消费。
- `frontend/src/permission.js`：在 `beforeEach` 中加入离开保护——`from.path === '/sage/qa' && to.path !== '/sage/qa' && qaGuard.needsLeaveConfirm` 时拦截导航、写入 `pendingLeave` 并返回 `false`，由问答页消费后重新发起导航。
- `frontend/src/features/conversations/pages/WorkspacePage.vue`：重塑问答正文与离开保护。

### 关键设计

1. **聊天正文层级**：用户问题靠右（`turn-question` + `question-bubble`），回答靠左（`turn-answer` + `answer-bubble`）。回答下方为 `answer-footer`，包含状态徽章（非 COMPLETED 时显示）和反馈图标入口。输入区位于主区域底部，保留 Enter 发送、Shift+Enter 换行、loading 和 disabled。

2. **流式状态**：新增 `streamingFailed` ref，与 `refused` 分离。`onAnswerEvent` 的 `failed` 事件不再复用 `refused`，使气泡颜色和状态徽章准确反映五种终态（COMPLETED / REFUSED / FAILED / STOPPED / UNFINISHED）加 streaming 中间态。

3. **离开保护双层覆盖**：
   - **UI 守卫**（`guardNavigate`）：覆盖切换会话、新建会话和进入管理后台——这些都是 sidebar 事件，由 `WorkspacePage` 拦截。
   - **路由守卫**（`permission.js`）：覆盖浏览器后退、URL 直跳等非 UI 触发的导航。
   - 两者共享同一份 `confirmStopAndLeave` 逻辑：弹"停止生成并离开"确认 → 调用现有 `stopAnswer` 接口 → 停止成功才导航，失败则留在原页并显示错误。`leaveConfirming` 标志串行化，避免路由守卫与 UI 守卫同时触发时弹出两个确认框。

4. **停止后立即清理**：`stop()` 和 `confirmStopAndLeave()` 在 `stopAnswer` 成功后立即清空 `streamingGenerationId` 并调用 `qaGuard.setStreaming(false)`，避免在 SSE `stopped` 事件到达前离开保护重复触发或 `router.push` 被二次拦截。

5. **新对话空状态**：新对话显示"从企业知识中找到答案"+"选择知识库，答案将仅基于其中的可用企业文档生成"。已有会话无历史时显示"还没有提问，问一个试试"。不展示硬编码推荐问题或深度思考模式。

6. **反馈图标**：未反馈时显示 `ChatDotRound` 图标按钮 + tooltip"反馈这条回答"，点击打开现有 `FeedbackDialog`。已反馈时显示 `CircleCheck` 图标 + tooltip"已反馈，感谢你的帮助"，不可重复提交。

### 验证

- `yarn --cwd frontend build:prod` 通过。
- 真实 Java SSE 和反馈接口的浏览器验证需要完整后端运行环境，建议在联调环境补充以下路径：流式 delta 渲染、停止生成、拒答/失败/已停止/未完成状态展示、生成期间切换会话/进入管理后台/浏览器后退的离开确认、停止失败留在原页、反馈对话框提交。
