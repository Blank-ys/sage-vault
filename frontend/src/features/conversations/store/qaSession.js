// 问答会话恢复状态与侧栏布局偏好。
//
// 该 store 只持有用于跨页面恢复的最小会话标识和桌面侧栏收起偏好，不复制任何 Java 业务状态机：
// - lastConversationId 记录用户离开问答页前最后活跃的会话 ID。
// - sidebarCollapsed 记录桌面侧栏是否收起，仅持久化布局偏好，不持久化任何问答正文。
//
// 从管理后台"返回问答"时，WorkspacePage 优先按该 ID 恢复离开前的会话；
// 无恢复位置（首次进入或刷新）时回退到最近的会话或新对话。
const SIDEBAR_COLLAPSED_KEY = 'qa-sidebar-collapsed'

const useQaSessionStore = defineStore('qaSession', {
  state: () => ({
    lastConversationId: null,
    sidebarCollapsed: localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true'
  }),
  actions: {
    setLastConversation(id) {
      this.lastConversationId = id
    },
    setSidebarCollapsed(value) {
      this.sidebarCollapsed = value
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(value))
    },
    clear() {
      this.lastConversationId = null
    }
  }
})

export default useQaSessionStore
