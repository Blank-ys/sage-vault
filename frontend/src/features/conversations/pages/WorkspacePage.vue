<template>
  <div class="qa-workbench" v-loading="loading">
    <!-- 桌面：静态侧栏，支持收起 -->
    <ConversationSidebar
      v-if="!isMobile"
      :conversations="filteredConversations"
      :active-id="activeId"
      :search-key="searchKey"
      :has-admin-access="hasAdminAccess"
      :collapsed="sidebarCollapsed"
      @update:search-key="(v) => (searchKey = v)"
      @select="select"
      @new="startNew"
      @admin="goAdmin"
      @rename="rename"
      @delete="remove"
      @toggle-collapse="toggleSidebarCollapse"
    />

    <!-- 移动端：抽屉式侧栏，选择会话后自动关闭 -->
    <el-drawer
      v-if="isMobile"
      v-model="drawerVisible"
      direction="ltr"
      :size="300"
      :with-header="false"
      class="qa-mobile-drawer"
    >
      <ConversationSidebar
        :conversations="filteredConversations"
        :active-id="activeId"
        :search-key="searchKey"
        :has-admin-access="hasAdminAccess"
        :collapsed="false"
        @update:search-key="(v) => (searchKey = v)"
        @select="onMobileSelect"
        @new="onMobileNew"
        @admin="goAdmin"
        @rename="rename"
        @delete="remove"
      />
    </el-drawer>

    <el-card class="qa-detail">
      <template #header>
        <div class="qa-detail-header">
          <el-button v-if="isMobile" text class="qa-sidebar-trigger" @click="drawerVisible = true">
            <el-icon><Menu /></el-icon>
          </el-button>
          <span class="qa-detail-title">{{ activeConversation ? displayTitle(activeConversation) : '新对话' }}</span>
          <el-tooltip
            v-if="activeConversation && !activeKnowledgeBaseDeleted"
            :content="activeConversation.knowledgeBaseName || '未知知识库'"
            placement="top"
          >
            <span class="qa-detail-kb">
              <el-icon><Collection /></el-icon>
              <span class="qa-detail-kb-name">{{ activeConversation.knowledgeBaseName }}</span>
            </span>
          </el-tooltip>
          <span v-else-if="activeConversation && activeKnowledgeBaseDeleted" class="qa-detail-kb deleted">
            <el-icon><Collection /></el-icon>知识库已删除
          </span>
          <el-select
            v-else
            v-model="knowledgeBaseId"
            placeholder="选择知识库"
            :loading="loading"
            class="qa-new-kb-select"
          >
            <el-option v-for="item in knowledgeBases" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </div>
      </template>

      <el-alert
        v-if="activeKnowledgeBaseDeleted"
        title="该会话的知识库已删除，历史问答仍可查看，但无法继续提问。"
        type="info"
        show-icon
        :closable="false"
        class="deleted-notice"
      />

      <div v-loading="historyLoading" class="history">
        <div v-if="!history.length && !streaming" class="qa-empty">
          <template v-if="!activeId">
            <h3 class="qa-empty-title">从企业知识中找到答案</h3>
            <p class="qa-empty-desc">选择知识库，答案将仅基于其中的可用企业文档生成</p>
          </template>
          <el-empty v-else description="还没有提问，问一个试试" :image-size="60" />
        </div>

        <div v-for="record in history" :key="record.id" class="qa-turn">
          <div class="turn-question">
            <div class="question-bubble">{{ record.question }}</div>
          </div>
          <div class="turn-answer">
            <div class="answer-bubble" :class="answerBubbleClass(record)">
              <div v-if="record.answer" class="answer-content" v-html="renderMarkdown(record.answer)" />
              <div v-else class="answer-status-text">{{ answerStatusText(record) }}</div>
            </div>
            <div class="answer-footer">
              <span v-if="record.status !== 'COMPLETED'" class="answer-status-badge" :class="statusBadgeClass(record)">
                {{ statusBadgeText(record) }}
              </span>
              <el-tooltip v-if="record.feedbackSubmitted" content="已反馈，感谢你的帮助" placement="top">
                <span class="feedback-icon done"><el-icon><CircleCheck /></el-icon></span>
              </el-tooltip>
              <el-tooltip v-else content="反馈这条回答" placement="top">
                <button class="feedback-icon" type="button" @click="openFeedback(record)">
                  <el-icon><ChatDotRound /></el-icon>
                </button>
              </el-tooltip>
            </div>
          </div>
        </div>

        <div v-if="streaming" class="qa-turn">
          <div class="turn-question">
            <div class="question-bubble">{{ streamingQuestion }}</div>
          </div>
          <div class="turn-answer">
            <div class="answer-bubble" :class="streamingBubbleClass">
              <div v-if="streamingHasContent" class="answer-content" v-html="renderMarkdown(streamingAnswer)" />
              <div v-else class="answer-status-text">{{ streamingAnswer || '正在处理问题…' }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="qa-input-area">
        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          :disabled="activeKnowledgeBaseDeleted"
          :placeholder="inputPlaceholder"
          @keydown="onQuestionKeydown"
        />
        <div class="qa-input-actions">
          <el-button v-if="canStop" type="warning" :loading="stopping" @click="onStop">停止生成</el-button>
          <el-button v-else type="primary" :loading="asking" :disabled="!canAsk" @click="onAsk">提问</el-button>
        </div>
      </div>

      <feedback-dialog v-model="feedbackVisible" :qa-id="feedbackQaId" @submitted="markFeedbackSubmitted" />
    </el-card>
  </div>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { useWindowSize } from '@vueuse/core'
import { Collection, ChatDotRound, CircleCheck, Menu } from '@element-plus/icons-vue'
import { listAvailableKnowledgeBases } from '@/features/knowledge-bases'
import { FeedbackDialog } from '@/features/feedback'
import { useQaGuardStore, useQaSessionStore, useQaSession } from '@/features/conversations'
import {
  createConversation,
  deleteConversation,
  listConversations,
  listQuestions,
  renameConversation
} from '../api/conversations'
import { renderMarkdown } from '../composables/useMarkdown'
import usePermissionStore from '@/store/modules/permission'
import ConversationSidebar from '../components/ConversationSidebar.vue'

const router = useRouter()
const route = useRoute()
const permissionStore = usePermissionStore()
const qaGuard = useQaGuardStore()
const qaSession = useQaSessionStore()

const {
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
} = useQaSession()

const knowledgeBases = ref([])
const knowledgeBaseId = ref()
const conversations = ref([])
const activeId = ref()
const searchKey = ref('')
const history = ref([])
const question = ref('')
const loading = ref(false)
const historyLoading = ref(false)
const feedbackVisible = ref(false)
const feedbackQaId = ref()

// 响应式断点：与 RuoYi layout 一致使用 992px 区分桌面/移动
const { width: windowWidth } = useWindowSize()
const MOBILE_BREAKPOINT = 992
const isMobile = computed(() => windowWidth.value - 1 < MOBILE_BREAKPOINT)

// 桌面侧栏收起状态：仅持久化布局偏好，不持久化问答正文
const sidebarCollapsed = computed(() => qaSession.sidebarCollapsed)
const drawerVisible = ref(false)

// 拥有至少一个后台动态菜单权限时才展示"管理后台"入口
const hasAdminAccess = computed(() => permissionStore.hasAdminAccess)

const activeConversation = computed(() => conversations.value.find(item => item.id === activeId.value))
// 知识库活动记录已被级联删除：会话只读，提问入口必须关闭
const activeKnowledgeBaseDeleted = computed(() => Boolean(activeConversation.value?.knowledgeBaseDeleted))
const canAsk = computed(() => Boolean(knowledgeBaseId.value) && Boolean(question.value.trim())
  && !asking.value && !activeKnowledgeBaseDeleted.value)

// 记录最后活跃会话，供管理后台"返回问答"时恢复
watch(activeId, (id) => {
  if (id) qaSession.setLastConversation(id)
})

// 仅根据已加载会话的标题过滤，不读取或持久化问题/回答正文
const filteredConversations = computed(() => {
  const key = searchKey.value.trim().toLowerCase()
  if (!key) return conversations.value
  return conversations.value.filter(item => (item.title || '').toLowerCase().includes(key))
})

const inputPlaceholder = computed(() => {
  if (activeKnowledgeBaseDeleted.value) return '知识库已删除，无法继续提问'
  if (!activeId.value && !knowledgeBaseId.value) return '请先在上方选择知识库，再输入你的问题'
  return '请输入你的问题（Enter 发送，Shift+Enter 换行）'
})

function goAdmin() {
  guardNavigate(() => router.push('/admin/index'))
}

// 桌面侧栏收起/展开：仅持久化布局偏好
function toggleSidebarCollapse() {
  qaSession.setSidebarCollapsed(!qaSession.sidebarCollapsed)
}

// 移动端选择会话后自动关闭抽屉
function onMobileSelect(conversationId) {
  drawerVisible.value = false
  select(conversationId)
}

// 移动端新建会话后自动关闭抽屉
function onMobileNew() {
  drawerVisible.value = false
  startNew()
}

// 无管理权限访问 /admin/* 时守卫会带 adminDenied=1 回到问答工作台，这里只提示一次并清理 URL
function consumeAdminDeniedOnce() {
  if (route.query.adminDenied !== '1') return
  ElMessage.warning('暂无管理后台权限')
  const { adminDenied, ...rest } = route.query
  router.replace({ path: route.path, query: rest })
}

function displayTitle(conversation) {
  return conversation.title?.trim() || '未命名会话'
}

async function load() {
  loading.value = true
  try {
    const [bases, items] = await Promise.all([listAvailableKnowledgeBases(), listConversations()])
    knowledgeBases.value = bases
    conversations.value = items
    if (items.length) {
      // 返回问答优先恢复离开前的问答会话，无恢复位置时选择最近的会话
      const lastId = qaSession.lastConversationId
      const restoreId = lastId && items.find(item => item.id === lastId) ? lastId : items[0].id
      await select(restoreId)
    }
  } finally {
    loading.value = false
  }
}

async function select(conversationId) {
  if (conversationId === activeId.value) return
  // 切换会话会卸载当前问答上下文：生成期间需要先停止并确认
  await guardNavigate(() => doSelect(conversationId))
}

async function doSelect(conversationId) {
  activeId.value = conversationId
  resetStream()
  // 切换会话时丢弃上一个会话的草稿输入，避免内容串台
  question.value = ''
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

async function startNew() {
  await guardNavigate(doStartNew)
}

function doStartNew() {
  activeId.value = undefined
  // 新会话须在右侧主区域显式选择知识库，未选择前不允许提问
  knowledgeBaseId.value = undefined
  history.value = []
  question.value = ''
  resetStream()
}

async function rename(conversationId) {
  const conversation = conversations.value.find(item => item.id === conversationId)
  const result = await ElMessageBox.prompt('请输入新的会话标题', '重命名会话', {
    inputValue: conversation?.title || '',
    inputValidator: value => (value && value.trim() ? true : '会话标题不能为空')
  }).catch(() => null)
  if (!result) return
  const updated = await renameConversation(conversationId, result.value.trim())
  conversations.value = conversations.value.map(item => (item.id === updated.id ? updated : item))
  ElMessage.success('会话已重命名')
}

async function remove(conversationId) {
  const conversation = conversations.value.find(item => item.id === conversationId)
  const confirmed = await ElMessageBox.confirm(
    `删除后该会话的全部提问与回答都会被清除，确认删除“${displayTitle(conversation)}”？`,
    '删除会话',
    { type: 'warning' }
  ).catch(() => false)
  if (!confirmed) return
  await deleteConversation(conversationId)
  conversations.value = conversations.value.filter(item => item.id !== conversationId)
  if (activeId.value === conversationId) doStartNew()
  ElMessage.success('会话已删除')
}

// 统一的离开保护：UI 触发的切换会话、新建会话和进入管理后台都经过这里。
// 生成未活跃时直接执行 action；生成活跃时弹"停止生成并离开"确认，
// 确认后调用显式停止接口，停止成功才执行 action，失败则留在原页。
async function guardNavigate(action) {
  if (!qaGuard.needsLeaveConfirm) {
    action()
    return
  }
  if (await confirmLeave(activeId.value)) {
    action()
  }
}

// 路由守卫拦截浏览器后退、URL 直跳等非 UI 触发的导航后，由问答页消费 pendingLeave：
// 展示同一份确认框，停止成功后重新发起导航，失败或取消则留在原页。
watch(() => qaGuard.pendingLeave, async (target) => {
  if (!target) return
  qaGuard.clearLeave()
  if (await confirmLeave(activeId.value)) {
    router.push(target.fullPath)
  }
})

function onQuestionKeydown(event) {
  if (event.key !== 'Enter') return
  // 中文输入法组合期间 Enter 用于选词，不触发发送
  if (event.isComposing || event.keyCode === 229) return
  // Shift+Enter：放行默认换行
  if (event.shiftKey) return
  // Enter：阻止默认换行并发送
  event.preventDefault()
  if (canAsk.value) onAsk()
}

async function onAsk() {
  // 新会话必须先在右侧主区域选择知识库
  if (!activeId.value && !knowledgeBaseId.value) {
    ElMessage.warning('请先在上方选择知识库')
    return
  }
  if (activeKnowledgeBaseDeleted.value) {
    ElMessage.warning('知识库已删除，无法继续提问')
    return
  }
  const questionText = question.value.trim()
  question.value = ''
  try {
    const conversationId = activeId.value ?? (await createNewConversation())
    if (await ask(conversationId, questionText)) {
      await refreshAfterAnswer(conversationId)
    }
  } catch (error) {
    if (error.name !== 'AbortError') ElMessage.error(error.message || '提问失败')
  }
}

async function createNewConversation() {
  const conversation = await createConversation(knowledgeBaseId.value)
  conversations.value = [conversation, ...conversations.value]
  activeId.value = conversation.id
  history.value = []
  return conversation.id
}

async function onStop() {
  try {
    await stop(activeId.value)
    ElMessage.success('已停止生成')
  } catch (error) {
    ElMessage.error(error.message || '停止失败')
  }
}

async function refreshAfterAnswer(conversationId) {
  conversations.value = await listConversations()
  activeId.value = conversationId
  history.value = await listQuestions(conversationId)
  resetStream()
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

onBeforeUnmount(() => {
  qaGuard.clearLeave()
})
// 一次性消费无管理权限提示：用 watch 覆盖组件复用（同路径不同 query）场景
watch(() => route.query.adminDenied, (val) => {
  if (val === '1') consumeAdminDeniedOnce()
}, { immediate: true })
load()
</script>

<style scoped>
.qa-workbench {
  display: flex;
  height: 100%;
  width: 100%;
}
.qa-detail {
  flex: 1;
  min-width: 0;
  margin: 16px;
  display: flex;
  flex-direction: column;
}
.qa-detail :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 16px;
}
.qa-detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.qa-detail-title {
  font-size: 16px;
  font-weight: 600;
  flex: none;
}
.qa-detail-kb {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 320px;
  padding: 2px 10px;
  border-radius: 14px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.qa-detail-kb.deleted {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
.qa-detail-kb-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.qa-new-kb-select {
  width: 240px;
}
.deleted-notice {
  margin-bottom: 16px;
}
.history {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin-bottom: 16px;
}
.qa-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.qa-empty-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.qa-empty-desc {
  margin: 0;
  font-size: 13px;
  max-width: 360px;
  line-height: 1.6;
}
.qa-turn {
  margin-bottom: 20px;
}
.turn-question {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}
.question-bubble {
  max-width: 80%;
  padding: 8px 14px;
  border-radius: 12px;
  background: var(--el-color-primary);
  color: #fff;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}
.turn-answer {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.answer-bubble {
  max-width: 85%;
  padding: 12px 16px;
  border-radius: 12px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  line-height: 1.7;
  word-break: break-word;
}
.answer-bubble.bubble-refused {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}
.answer-bubble.bubble-failed {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger-dark-2);
}
.answer-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  padding-left: 4px;
}
.answer-status-badge {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
}
.answer-status-badge.badge-warning {
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
}
.answer-status-badge.badge-error {
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
}
.answer-status-badge.badge-info {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
}
.feedback-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  padding: 0;
  transition: background 0.15s, color 0.15s;
}
.feedback-icon:hover {
  background: var(--el-fill-color);
  color: var(--el-color-primary);
}
.feedback-icon.done {
  color: var(--el-color-success);
  cursor: default;
}
.feedback-icon.done:hover {
  background: transparent;
  color: var(--el-color-success);
}
.qa-input-area {
  flex: none;
}
.qa-input-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
.answer-content {
  line-height: 1.7;
  word-break: break-word;
}
.answer-content :deep(h1),
.answer-content :deep(h2),
.answer-content :deep(h3) {
  margin: 0.6em 0 0.3em;
}
.answer-content :deep(p) {
  margin: 0.4em 0;
}
.answer-content :deep(ul),
.answer-content :deep(ol) {
  margin: 0.4em 0;
  padding-left: 1.5em;
}
.answer-content :deep(pre) {
  background: var(--el-fill-color);
  padding: 8px 12px;
  border-radius: 4px;
  overflow-x: auto;
}
.answer-content :deep(code) {
  font-family: var(--el-font-family-mono, monospace);
}
.answer-content :deep(table) {
  border-collapse: collapse;
  margin: 0.4em 0;
}
.answer-content :deep(th),
.answer-content :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 4px 8px;
}
.answer-content :deep(blockquote) {
  margin: 0.4em 0;
  padding-left: 1em;
  color: var(--el-text-color-secondary);
  border-left: 3px solid var(--el-border-color);
}
.answer-status-text {
  white-space: pre-wrap;
}

/* 移动端侧栏触发按钮 */
.qa-sidebar-trigger {
  flex: none;
  padding: 4px 8px;
  height: 32px;
  color: var(--el-text-color-primary);
}

/* 响应式：中等宽度适配 */
@media screen and (max-width: 1280px) {
  .qa-detail {
    margin: 12px;
  }
  .qa-detail :deep(.el-card__body) {
    padding: 12px;
  }
  .qa-new-kb-select {
    width: 200px;
  }
}

/* 响应式：移动端 390x844 等 */
@media screen and (max-width: 991px) {
  .qa-detail {
    margin: 8px;
  }
  .qa-detail :deep(.el-card__body) {
    padding: 10px;
  }
  .qa-detail-header {
    flex-wrap: wrap;
    gap: 8px;
  }
  .qa-detail-title {
    font-size: 15px;
    max-width: calc(100% - 48px);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .qa-new-kb-select {
    width: 100%;
  }
  .qa-detail-kb {
    max-width: 100%;
  }
  .question-bubble {
    max-width: 90%;
  }
  .answer-bubble {
    max-width: 95%;
  }
  .qa-input-actions {
    flex-wrap: wrap;
  }
  .qa-empty-desc {
    max-width: 280px;
  }
}

/* 响应式：极窄屏 */
@media screen and (max-width: 480px) {
  .qa-detail {
    margin: 4px;
  }
  .qa-detail :deep(.el-card__body) {
    padding: 8px;
  }
  .qa-empty-title {
    font-size: 16px;
  }
  .qa-input-actions {
    justify-content: stretch;
  }
  .qa-input-actions .el-button {
    flex: 1;
  }
}
</style>

<style lang="scss">
/* 移动端抽屉内部样式 */
.qa-mobile-drawer .el-drawer__body {
  padding: 0;
  height: 100%;
}
</style>
