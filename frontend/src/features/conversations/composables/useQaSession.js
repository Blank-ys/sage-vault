import { ElMessage, ElMessageBox } from 'element-plus'
import { askQuestion, stopAnswer } from '../api/conversations'
import useQaGuardStore from '../store/qaGuard'

// 单活跃流问答生命周期 composable。
//
// 承载从"发起提问"到"流自然结束 / 被停止 / 被取消"的完整流式状态机，
// 以及状态 → 文案/样式映射和离开保护。WorkspacePage 只消费本模块返回值，
// 不接触原始 SSE 帧、AbortController 或流式 ref。
//
// 这是 conversations feature 的公开 composable interface，
// 由 features/conversations/index.js 导出，前端状态测试命中该 seam。
export function useQaSession() {
  const qaGuard = useQaGuardStore()

  const streaming = ref(false)
  const refused = ref(false)
  const streamingFailed = ref(false)
  const asking = ref(false)
  const stopping = ref(false)
  const streamingQuestion = ref('')
  const streamingAnswer = ref('')
  const streamingGenerationId = ref('')
  let controller

  const canStop = computed(() => asking.value && Boolean(streamingGenerationId.value))

  // 生成是否处于"用户尚未显式停止"的活跃状态：路由守卫和 UI 守卫都依赖该信号。
  // asking 单独为 true（请求已发出但 started 未到达）不足以触发离开保护，
  // 因为没有 generationId 无法调用停止接口。
  const isGenerating = computed(() => asking.value && Boolean(streamingGenerationId.value) && !stopping.value)

  watch(isGenerating, (val) => qaGuard.setStreaming(val))

  // 流式回答只有非占位、非拒绝、非失败的正文才走 Markdown 渲染
  const streamingHasContent = computed(() =>
    Boolean(streamingAnswer.value)
    && streamingAnswer.value !== '正在处理问题…'
    && !refused.value
    && !streamingFailed.value
  )

  const streamingBubbleClass = computed(() => {
    if (refused.value) return 'bubble-refused'
    if (streamingFailed.value) return 'bubble-failed'
    return ''
  })

  // 已停止与未完成都保留残缺正文，只有完全没有正文时才提示中断原因
  function answerStatusText(record) {
    if (record.status === 'STOPPED') return '本次回答已停止'
    if (record.status === 'FAILED') return '本次回答生成失败'
    if (record.status === 'REFUSED') return '本次回答已拒答'
    return '本次回答未完成'
  }

  function answerBubbleClass(record) {
    if (record.status === 'REFUSED') return 'bubble-refused'
    if (record.status === 'FAILED') return 'bubble-failed'
    return ''
  }

  function statusBadgeText(record) {
    if (record.status === 'REFUSED') return '已拒答'
    if (record.status === 'FAILED') return '生成失败'
    if (record.status === 'STOPPED') return '已停止'
    return '未完成'
  }

  function statusBadgeClass(record) {
    if (record.status === 'FAILED') return 'badge-error'
    if (record.status === 'REFUSED' || record.status === 'STOPPED') return 'badge-warning'
    return 'badge-info'
  }

  // 流式事件 reducer：把 Java SSE 事件应用到当前活跃流状态。
  // 原始 SSE frame 已由 api adapter 转换为 feature 自有事件，本函数只负责应用。
  function onAnswerEvent(event) {
    if (event.type === 'started') {
      streamingGenerationId.value = event.generationId
    } else if (event.type === 'delta') {
      streamingAnswer.value = streamingAnswer.value === '正在处理问题…' ? event.delta : streamingAnswer.value + event.delta
    } else if (event.type === 'refused') {
      refused.value = true
      streamingAnswer.value = event.message
    } else if (event.type === 'failed') {
      // Python 生成中途失败：detail 已是脱敏后的受控失败类别，不暴露原始异常/知识库 id。
      streamingFailed.value = true
      streamingAnswer.value = '回答生成失败，请稍后重试。' + (event.detail ? `（${event.detail}）` : '')
    } else if (event.type === 'stopped') {
      // 已经收到的内容保持展示，终态由后端裁决后在历史里体现为"已停止"
      streamingGenerationId.value = ''
    }
  }

  // 发起提问：运行完整流式生命周期。
  // - 流自然结束（含用户停止后后端裁决收尾）时 resolve true；
  // - 连接被取消（AbortError，业务终态由后端另行裁决）时 resolve false；
  // - 非取消的失败先写入 streamingFailed/streamingAnswer，再抛出错误，
  //   调用方负责用户提示并决定是否刷新记录。
  async function ask(conversationId, question) {
    asking.value = true
    refused.value = false
    streamingFailed.value = false
    streaming.value = true
    streamingQuestion.value = question
    streamingAnswer.value = '正在处理问题…'
    controller = new AbortController()
    try {
      await askQuestion(conversationId, question, onAnswerEvent, controller.signal)
      return true
    } catch (error) {
      if (error.name !== 'AbortError') {
        streamingFailed.value = true
        streamingAnswer.value = error.message || '提问失败'
        throw error
      }
      return false
    } finally {
      asking.value = false
      controller = undefined
    }
  }

  // 用户显式停止：调用 Java 停止接口裁决终态，不断开浏览器连接，
  // 后端裁决后由流上的 'stopped' 事件自然收尾，已收内容保持展示。
  // 若尚未拿到 generationId（started 事件未到达），无法定位生成，只本地中断连接并清理，
  // 不发后端请求，避免畸形 POST 与 GET 端点碰撞。
  async function stop(conversationId) {
    stopping.value = true
    try {
      if (!streamingGenerationId.value) {
        controller?.abort()
        streamingGenerationId.value = ''
        qaGuard.setStreaming(false)
        resetStream()
        return
      }
      await stopAnswer(conversationId, streamingGenerationId.value)
      // 停止接口已成功：立即清空 generationId，避免离开保护在 'stopped' 事件到达前重复触发
      streamingGenerationId.value = ''
      qaGuard.setStreaming(false)
    } finally {
      stopping.value = false
    }
  }

  // 离开保护：展示"停止生成并离开"确认，确认后停止当前生成并清理活跃状态。
  // 返回 true 表示可以继续导航（已停止或本来就没有活跃生成）；
  // false 表示应留在原页（取消确认、停止失败或已有一次确认在途）。
  // 路由守卫写入 qaGuard.pendingLeave 后由页面 watch 消费，复用同一份逻辑。
  let leaveConfirming = false
  async function confirmLeave(conversationId) {
    // 生成已自然结束（generationId 被清空），无需停止
    if (!isGenerating.value) return true
    if (leaveConfirming) return false
    leaveConfirming = true
    try {
      try {
        await ElMessageBox.confirm(
          '当前正在生成回答，离开会先停止生成。是否停止生成并离开？',
          '停止生成并离开',
          { confirmButtonText: '停止生成并离开', cancelButtonText: '取消', type: 'warning' }
        )
      } catch {
        return false
      }
      try {
        // 尚未拿到 generationId 时无法定位生成，只本地中断连接并清理，不发后端请求
        if (!streamingGenerationId.value) {
          controller?.abort()
          streamingGenerationId.value = ''
          qaGuard.setStreaming(false)
          resetStream()
        } else {
          await stopAnswer(conversationId, streamingGenerationId.value)
        }
      } catch (error) {
        ElMessage.error(error.message || '停止失败，已留在当前页面')
        return false
      }
      // 停止成功：中断 SSE 读取并立即清理本地活跃状态，避免后续导航触发二次守卫
      controller?.abort()
      streamingGenerationId.value = ''
      qaGuard.setStreaming(false)
      resetStream()
      ElMessage.success('已停止生成')
      return true
    } finally {
      leaveConfirming = false
    }
  }

  // 清理当前流上下文：切换会话 / 新建会话 / 流结束刷新后调用
  function resetStream() {
    streaming.value = false
    refused.value = false
    streamingFailed.value = false
    streamingQuestion.value = ''
    streamingAnswer.value = ''
    streamingGenerationId.value = ''
  }

  onBeforeUnmount(() => {
    // 断开连接只管理 transport，不调用业务停止接口：终态由后端裁决
    controller?.abort()
    qaGuard.setStreaming(false)
  })

  return {
    streaming,
    streamingQuestion,
    streamingAnswer,
    asking,
    stopping,
    canStop,
    streamingHasContent,
    streamingBubbleClass,
    answerStatusText,
    answerBubbleClass,
    statusBadgeText,
    statusBadgeClass,
    ask,
    stop,
    confirmLeave,
    resetStream
  }
}
