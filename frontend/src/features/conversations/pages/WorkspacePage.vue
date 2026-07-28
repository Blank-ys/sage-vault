<template>
  <div class="app-container workspace">
    <el-card>
      <template #header>知识问答</template>
      <el-select v-model="knowledgeBaseId" placeholder="选择知识库" :loading="loading" class="full-width">
        <el-option v-for="item in knowledgeBases" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-model="question" type="textarea" :rows="5" maxlength="2000" show-word-limit placeholder="请输入你的问题" />
      <el-button type="primary" :loading="asking" :disabled="!knowledgeBaseId || !question.trim()" @click="ask">提问</el-button>
      <el-alert v-if="answer" :title="answer" :type="refused ? 'warning' : 'info'" show-icon :closable="false" />
    </el-card>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { listAvailableKnowledgeBases } from '@/features/knowledge-bases'
import { askQuestion, createConversation } from '../api/conversations'

const knowledgeBases = ref([])
const knowledgeBaseId = ref()
const question = ref('')
const answer = ref('')
const refused = ref(false)
const loading = ref(false)
const asking = ref(false)
let controller

async function load() {
  loading.value = true
  try { knowledgeBases.value = await listAvailableKnowledgeBases() } finally { loading.value = false }
}

async function ask() {
  asking.value = true
  refused.value = false
  answer.value = '正在处理问题…'
  controller = new AbortController()
  try {
    const conversation = await createConversation(knowledgeBaseId.value)
    await askQuestion(conversation.id, question.value, event => {
      if (event.type === 'delta') {
        answer.value = answer.value === '正在处理问题…' ? event.delta : answer.value + event.delta
      } else if (event.type === 'refused') {
        refused.value = true
        answer.value = event.message
      }
    }, controller.signal)
  } catch (error) {
    if (error.name !== 'AbortError') {
      const message = error.message || '提问失败'
      answer.value = message
      ElMessage.error(message)
    }
  } finally { asking.value = false; controller = undefined }
}

onBeforeUnmount(() => controller?.abort())
load()
</script>

<style scoped>
.workspace { max-width: 860px; margin: 0 auto; }
.full-width, .el-textarea, .el-button, .el-alert { width: 100%; margin-bottom: 20px; }
</style>
