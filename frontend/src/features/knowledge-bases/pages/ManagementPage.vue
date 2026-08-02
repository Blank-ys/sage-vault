<template>
  <div class="app-container">
    <el-card>
      <template #header>
        <div class="header">
          <span>知识库管理</span>
          <el-button type="primary" @click="openCreate">新建知识库</el-button>
        </div>
      </template>
      <el-table v-loading="loading" :data="items">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="description" label="描述" />
        <el-table-column label="状态" width="140">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)" disable-transitions>
              {{ statusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="删除失败原因" min-width="200">
          <template #default="scope">
            <span v-if="scope.row.status === 'DELETE_FAILED'" class="failure">{{ scope.row.errorMessage }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="scope">
            <el-button link type="primary" :disabled="inDeleteFlow(scope.row)" @click="openEdit(scope.row)">
              编辑
            </el-button>
            <el-button link type="danger" :disabled="isDeleting(scope.row)" @click="confirmDelete(scope.row)">
              {{ scope.row.status === 'DELETE_FAILED' ? '重试删除' : '删除' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑知识库' : '新建知识库'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { createKnowledgeBase, deleteKnowledgeBase, listKnowledgeBases, updateKnowledgeBase } from '../api/knowledgeBases'

const STATUS_LABELS = {
  AVAILABLE: '可用',
  UNAVAILABLE: '不可用',
  DELETING: '删除中',
  DELETE_FAILED: '删除失败'
}

const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const items = ref([])
const form = reactive({ id: null, name: '', description: '' })
let pollTimer = null

function statusLabel(status) { return STATUS_LABELS[status] || status }

function statusTagType(status) {
  if (status === 'AVAILABLE') return 'success'
  if (status === 'DELETING') return 'warning'
  if (status === 'DELETE_FAILED') return 'danger'
  return 'info'
}

// 删除中的知识库正在被后台清理，不接受再次删除
function isDeleting(item) { return item.status === 'DELETING' }

// 进入删除流程后只允许查看与重试删除：编辑会让一个正在消失的知识库看起来重新可用
function inDeleteFlow(item) { return item.status === 'DELETING' || item.status === 'DELETE_FAILED' }

async function load() {
  loading.value = true
  try { items.value = await listKnowledgeBases() } finally { loading.value = false }
  scheduleDeletingPoll()
}

// 删除在后台异步推进，页面轮询直到没有知识库处于删除中
function scheduleDeletingPoll() {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
  if (!items.value.some(isDeleting)) return
  pollTimer = setTimeout(async () => {
    items.value = await listKnowledgeBases()
    scheduleDeletingPoll()
  }, 3000)
}

onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer) })

function openCreate() { Object.assign(form, { id: null, name: '', description: '' }); visible.value = true }
function openEdit(item) { Object.assign(form, item); visible.value = true }

async function confirmDelete(item) {
  const retrying = item.status === 'DELETE_FAILED'
  // 重试面对的是"已知失败的残留"，提示要落在继续清理上，不能沿用首次删除的措辞
  const message = retrying
    ? `知识库「${item.name}」上次清理未完成，重试将继续清理剩余的文档、原文件与向量数据。`
      + '已清理的内容不会被重复删除。确认重试删除？'
    : `删除知识库「${item.name}」将同时清除其全部文档、原文件与向量数据，且无法恢复。`
      + '历史问答记录会保留并标记为“知识库已删除”。确认删除？'
  try {
    await ElMessageBox.confirm(message, retrying ? '确认重试删除' : '确认删除知识库',
      {
        type: 'warning',
        confirmButtonText: retrying ? '确认重试' : '确认删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      })
  } catch {
    // 用户取消删除，知识库保持原状
    return
  }
  await deleteKnowledgeBase(item.id)
  ElMessage.success(retrying ? '已重新开始清理，完成后知识库将从列表移除' : '已开始删除，清理完成后知识库将从列表移除')
  await load()
}

async function save() {
  if (!form.name.trim()) return ElMessage.warning('请输入知识库名称')
  saving.value = true
  try {
    const data = { name: form.name, description: form.description }
    if (form.id) await updateKnowledgeBase(form.id, data); else await createKnowledgeBase(data)
    visible.value = false
    ElMessage.success('保存成功')
    await load()
  } finally { saving.value = false }
}

load()
</script>

<style scoped>
.header { display: flex; align-items: center; justify-content: space-between; }
.failure { color: var(--el-color-danger); }
</style>
