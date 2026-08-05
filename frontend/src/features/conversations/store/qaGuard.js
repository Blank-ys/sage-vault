// 问答生成期间的离开保护状态。
//
// 该 store 只持有 UI/生命周期标志，不复制任何 Java 业务状态机：
// - streaming 为 true 表示当前问答页存在尚未显式停止的活跃生成；
// - pendingLeave 由路由守卫写入，由问答页消费并清理。
//
// 离开的"停止生成并离开"确认与停止接口调用由问答页自身完成，
// 本 store 只负责在问答页与全局路由守卫之间传递最小信号。
const useQaGuardStore = defineStore('qaGuard', {
  state: () => ({
    streaming: false,
    pendingLeave: null
  }),
  getters: {
    needsLeaveConfirm: (state) => state.streaming
  },
  actions: {
    setStreaming(value) {
      this.streaming = value
    },
    requestLeave(target) {
      this.pendingLeave = target
    },
    clearLeave() {
      this.pendingLeave = null
    }
  }
})

export default useQaGuardStore
