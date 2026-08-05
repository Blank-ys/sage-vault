// 问答会话恢复状态。
//
// 该 store 只持有用于跨页面恢复的最小会话标识，不复制任何 Java 业务状态机：
// - lastConversationId 记录用户离开问答页前最后活跃的会话 ID。
//
// 从管理后台"返回问答"时，WorkspacePage 优先按该 ID 恢复离开前的会话；
// 无恢复位置（首次进入或刷新）时回退到最近的会话或新对话。
const useQaSessionStore = defineStore('qaSession', {
  state: () => ({
    lastConversationId: null
  }),
  actions: {
    setLastConversation(id) {
      this.lastConversationId = id
    },
    clear() {
      this.lastConversationId = null
    }
  }
})

export default useQaSessionStore
