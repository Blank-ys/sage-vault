<template>
  <div class="app-container workspace">
    <el-card class="conversation-list">
      <template #header>
        <div class="list-header">
          <span>我的会话</span>
          <el-button type="primary" link :disabled="!knowledgeBaseId" @click="startNew">新建会话</el-button>
        </div>
      </template>
      <el-select v-model="knowledgeBaseId" placeholder="选择知识库" :loading="loading" class="full-width">
        <el-option v-for="item in knowledgeBases" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-empty v-if="!conversations.length" description="暂无会话" :image-size="60" />
      <ul v-else class="conversation-items">
        <li
          v-for="item in conversations"
          :key="item.id"
          :class="['conversation-item', { active: item.id === activeId }]"
          @click="select(item.id)"
        >
          <span class="conversation-title" :title="displayTitle(item)">
            {{ displayTitle(item) }}
            <!-- 知识库已被级联删除：历史仍可读，但不能继续提问 -->
            <el-tag v-if="item.knowledgeBaseDeleted" type="info" size="small" disable-transitions>
              知识库已删除
            </el-tag>
          </span>
          <span class="conversation-actions">
            <el-button type="primary" link @click.stop="rename(item)">改名</el-button>
            <el-button type="danger" link @click.stop="remove(item)">删除</el-button>
          </span>
        </li>
      </ul>
    </el-card>

    <el-card class="conversation-detail">
      <template #header>{{ activeConversation ? displayTitle(activeConversation) : '知识问答' }}</template>
      <el-alert
        v-if="activeKnowledgeBaseDeleted"
        title="该会话的知识库已删除，历史问答仍可查看，但无法继续提问。"
        type="info"
        show-icon
        :closable="false"
        class="deleted-notice"
      />
      <div v-loading="historyLoading" class="history">
        <el-empty v-if="!history.length" description="还没有提问，问一个试试" :image-size="60" />
        <div v-for="record in history" :key="record.id" class="history-item">
          <p class="history-question">{{ record.question }}</p>
          <el-alert
            :type="statusType(record)"
            show-icon
            :closable="false"
          >
            <div v-if="record.answer" class="answer-content" v-html="renderMarkdown(record.answer)" />
            <div v-else class="answer-status-text">{{ answerStatusText(record) }}</div>
          </el-alert>
          <div class="history-feedback">
            <!-- 已反馈的问答收敛为状态文案，避免重复提交 -->
            <span v-if="record.feedbackSubmitted" class="feedback-submitted">已反馈，感谢你的帮助</span>
            <el-button v-else type="primary" link @click="openFeedback(record)">反馈这条回答</el-button>
          </div>
        </div>
        <div v-if="streaming" class="history-item">
          <p class="history-question">{{ streamingQuestion }}</p>
          <el-alert :type="refused ? 'warning' : 'info'" show-icon :closable="false">
            <div class="answer-content" v-html="renderMarkdown(streamingAnswer)" />
          </el-alert>
        </div>
      </div>
      <el-input
        v-model="question"
        type="textarea"
        :rows="5"
        maxlength="2000"
        show-word-limit
        :disabled="activeKnowledgeBaseDeleted"
        :placeholder="activeKnowledgeBaseDeleted ? '知识库已删除，无法继续提问' : '请输入你的问题（Enter 发送，Shift+Enter 换行）'"
        @keydown="onQuestionKeydown"
      />
      <el-button v-if="canStop" type="warning" :loading="stopping" @click="stop">停止生成</el-button>
      <el-button v-else type="primary" :loading="asking" :disabled="!canAsk" @click="ask">提问</el-button>
    </el-card>

    <feedback-dialog v-model="feedbackVisible" :qa-id="feedbackQaId" @submitted="markFeedbackSubmitted" />
  </div>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAvailableKnowledgeBases } from '@/features/knowledge-bases'
import { FeedbackDialog } from '@/features/feedback'
import {
  askQuestion,
  createConversation,
  deleteConversation,
  listConversations,
  listQuestions,
  renameConversation,
  stopAnswer
} from '../api/conversations'
import { renderMarkdown } from '../composables/useMarkdown'

const knowledgeBases = ref([])
const knowledgeBaseId = ref()
const conversations = ref([])
const activeId = ref()
const history = ref([])
const question = ref('')
const streamingQuestion = ref('')
const streamingAnswer = ref('')
const streaming = ref(false)
const refused = ref(false)
const loading = ref(false)
const historyLoading = ref(false)
const asking = ref(false)
const stopping = ref(false)
const streamingGenerationId = ref('')
const feedbackVisible = ref(false)
const feedbackQaId = ref()
let controller

const activeConversation = computed(() => conversations.value.find(item => item.id === activeId.value))
// 知识库活动记录已被级联删除：会话只读，提问入口必须关闭
const activeKnowledgeBaseDeleted = computed(() => Boolean(activeConversation.value?.knowledgeBaseDeleted))
const canAsk = computed(() => Boolean(knowledgeBaseId.value) && Boolean(question.value.trim())
  && !asking.value && !activeKnowledgeBaseDeleted.value)
const canStop = computed(() => asking.value && Boolean(streamingGenerationId.value))

function displayTitle(conversation) {
  return conversation.title?.trim() || '未命名会话'
}

// 已停止与未完成都保留残缺正文，只有完全没有正文时才提示中断原因
function answerStatusText(record) {
  if (record.status === 'STOPPED') return '本次回答已停止'
  if (record.status === 'FAILED') return '本次回答生成失败'
  return '本次回答未完成'
}

function statusType(record) {
  if (record.status === 'REFUSED' || record.status === 'STOPPED') return 'warning'
  if (record.status === 'FAILED') return 'error'
  return record.status === 'COMPLETED' ? 'info' : 'error'
}

async function load() {
  loading.value = true
  try {
    const [bases, items] = await Promise.all([listAvailableKnowledgeBases(), listConversations()])
    knowledgeBases.value = bases
    conversations.value = items
    if (!knowledgeBaseId.value && bases.length) knowledgeBaseId.value = bases[0].id
    if (items.length) await select(items[0].id)
  } finally {
    loading.value = false
  }
}

async function select(conversationId) {
  activeId.value = conversationId
  resetStreaming()
  const conversation = conversations.value.find(item => item.id === conversationId)
  // 知识库已删除的会话不回填选择器，避免把已消失的知识库当作可提问目标
  if (conversation && !conversation.knowledgeBaseDeleted) knowledgeBaseId.value = conversation.knowledgeBaseId
  historyLoading.value = true
  try {
    history.value = await listQuestions(conversationId)
  } catch (error) {
    history.value = []
    ElMessage.error(error.message || '读取会话历史失败')
  } finally {
    historyLoading.value = false
  }
}

function startNew() {
  activeId.value = undefined
  history.value = []
  question.value = ''
  resetStreaming()
}

async function rename(conversation) {
  const result = await ElMessageBox.prompt('请输入新的会话标题', '重命名会话', {
    inputValue: conversation.title || '',
    inputValidator: value => (value && value.trim() ? true : '会话标题不能为空')
  }).catch(() => null)
  if (!result) return
  const updated = await renameConversation(conversation.id, result.value.trim())
  conversations.value = conversations.value.map(item => (item.id === updated.id ? updated : item))
  ElMessage.success('会话已重命名')
}

async function remove(conversation) {
  const confirmed = await ElMessageBox.confirm(
    `删除后该会话的全部提问与回答都会被清除，确认删除“${displayTitle(conversation)}”？`,
    '删除会话',
    { type: 'warning' }
  ).catch(() => false)
  if (!confirmed) return
  await deleteConversation(conversation.id)
  conversations.value = conversations.value.filter(item => item.id !== conversation.id)
  if (activeId.value === conversation.id) startNew()
  ElMessage.success('会话已删除')
}

function onQuestionKeydown(event) {
  if (event.key !== 'Enter') return
  // 中文输入法组合期间 Enter 用于选词，不触发发送
  if (event.isComposing || event.keyCode === 229) return
  // Shift+Enter：放行默认换行
  if (event.shiftKey) return
  // Enter：阻止默认换行并发送
  event.preventDefault()
  if (canAsk.value) ask()
}

async function ask() {
  asking.value = true
  refused.value = false
  streaming.value = true
  streamingQuestion.value = question.value.trim()
  streamingAnswer.value = '正在处理问题…'
  controller = new AbortController()
  try {
    const conversationId = activeId.value ?? (await createNewConversation())
    await askQuestion(conversationId, streamingQuestion.value, onAnswerEvent, controller.signal)
    question.value = ''
    await refreshAfterAnswer(conversationId)
  } catch (error) {
    if (error.name !== 'AbortError') {
      const message = error.message || '提问失败'
      streamingAnswer.value = message
      ElMessage.error(message)
    }
  } finally {
    asking.value = false
    controller = undefined
  }
}

async function createNewConversation() {
  const conversation = await createConversation(knowledgeBaseId.value)
  conversations.value = [conversation, ...conversations.value]
  activeId.value = conversation.id
  history.value = []
  return conversation.id
}

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
    refused.value = true
    streamingAnswer.value = '回答生成失败，请稍后重试。' + (event.detail ? `（${event.detail}）` : '')
  } else if (event.type === 'stopped') {
    // 已经收到的内容保持展示，终态由后端裁决后在历史里体现为"已停止"
    streamingGenerationId.value = ''
  }
}

async function stop() {
  stopping.value = true
  try {
    await stopAnswer(activeId.value, streamingGenerationId.value)
    ElMessage.success('已停止生成')
  } catch (error) {
    ElMessage.error(error.message || '停止失败')
  } finally {
    stopping.value = false
  }
}

async function refreshAfterAnswer(conversationId) {
  conversations.value = await listConversations()
  activeId.value = conversationId
  history.value = await listQuestions(conversationId)
  resetStreaming()
}

function openFeedback(record) {
  feedbackQaId.value = record.id
  feedbackVisible.value = true
}

function markFeedbackSubmitted(qaId) {
  history.value = history.value.map(record =>
    record.id === qaId ? { ...record, feedbackSubmitted: true } : record
  )
}

function resetStreaming() {
  streaming.value = false
  refused.value = false
  streamingQuestion.value = ''
  streamingAnswer.value = ''
  streamingGenerationId.value = ''
}

onBeforeUnmount(() => controller?.abort())
load()
</script>

<style scoped>
.workspace { display: flex; gap: 20px; align-items: flex-start; }
.conversation-list { width: 300px; flex: none; }
.conversation-detail { flex: 1; min-width: 0; }
.list-header { display: flex; justify-content: space-between; align-items: center; }
.full-width { width: 100%; margin-bottom: 16px; }
.conversation-items { list-style: none; margin: 0; padding: 0; }
.conversation-item {
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  padding: 8px 10px; border-radius: 4px; cursor: pointer;
}
.conversation-item:hover { background: var(--el-fill-color-light); }
.conversation-item.active { background: var(--el-color-primary-light-9); }
.conversation-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-actions { flex: none; }
.deleted-notice { margin-bottom: 16px; }
.history { min-height: 120px; margin-bottom: 20px; }
.history-item { margin-bottom: 16px; }
.history-question { margin: 0 0 8px; font-weight: 600; }
.history-feedback { margin-top: 4px; text-align: right; }
.feedback-submitted { font-size: 12px; color: var(--el-text-color-secondary); }
.el-textarea, .el-button { width: 100%; margin-bottom: 20px; }
.answer-content { line-height: 1.7; word-break: break-word; }
.answer-content :deep(h1),
.answer-content :deep(h2),
.answer-content :deep(h3) { margin: 0.6em 0 0.3em; }
.answer-content :deep(p) { margin: 0.4em 0; }
.answer-content :deep(ul),
.answer-content :deep(ol) { margin: 0.4em 0; padding-left: 1.5em; }
.answer-content :deep(pre) { background: var(--el-fill-color-light); padding: 8px 12px; border-radius: 4px; overflow-x: auto; }
.answer-content :deep(code) { font-family: var(--el-font-family-mono, monospace); }
.answer-content :deep(table) { border-collapse: collapse; margin: 0.4em 0; }
.answer-content :deep(th),
.answer-content :deep(td) { border: 1px solid var(--el-border-color); padding: 4px 8px; }
.answer-content :deep(blockquote) { margin: 0.4em 0; padding-left: 1em; color: var(--el-text-color-secondary); border-left: 3px solid var(--el-border-color); }
.answer-status-text { white-space: pre-wrap; }
</style>
