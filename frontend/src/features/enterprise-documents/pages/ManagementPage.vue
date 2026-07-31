<template>
  <div class="app-container">
    <el-card>
      <template #header>
        <div class="header">
          <span>企业文档</span>
        </div>
      </template>

      <el-alert
        class="bailian-notice"
        type="warning"
        :closable="false"
        show-icon
        title="提示：用户问题和检索到的文档片段将发送到阿里云百炼进行回答生成。本提示不构成敏感分类或审批，请自行判断上传文档的敏感性。"
      />

      <el-form :inline="true" class="search-form">
        <el-form-item label="知识库">
          <el-select v-model="selectedKnowledgeBaseId" placeholder="请选择知识库" clearable @change="load">
            <el-option
              v-for="item in knowledgeBases"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-upload
            ref="uploadRef"
            action=""
            :auto-upload="false"
            :show-file-list="true"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            accept=".txt,.pdf,.docx,.md"
            multiple
          >
            <el-button type="primary" :disabled="!selectedKnowledgeBaseId">选择文件</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item>
          <el-button
            type="success"
            :loading="uploading"
            :disabled="!pendingFiles.length || !selectedKnowledgeBaseId"
            @click="startBatchUpload"
          >开始上传</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="items">
        <el-table-column prop="filename" label="文件名" />
        <el-table-column prop="normalizedName" label="规范化名称" />
        <el-table-column prop="size" label="大小（字节）" width="120" />
        <el-table-column label="状态" width="120">
          <template #default="scope">
            <el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.status === 'FAILED'"
              type="primary"
              size="small"
              :loading="retryingId === scope.row.id"
              @click="handleRetry(scope.row)"
            >重试</el-button>
            <el-button
              v-if="scope.row.status === 'AVAILABLE'"
              type="danger"
              size="small"
              :loading="deletingId === scope.row.id"
              @click="handleDelete(scope.row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { listKnowledgeBases } from '@/features/knowledge-bases'
import { deleteDocument, listDocuments, retryDocument, uploadDocuments } from '../api/documents'

const loading = ref(false)
const uploading = ref(false)
const retryingId = ref(null)
const deletingId = ref(null)
const selectedKnowledgeBaseId = ref(null)
const knowledgeBases = ref([])
const items = ref([])
const uploadRef = ref(null)
const pendingFiles = ref([])

async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = await listKnowledgeBases()
  } catch {
    ElMessage.error('加载知识库失败')
  }
}

async function load() {
  if (!selectedKnowledgeBaseId.value) {
    items.value = []
    return
  }
  loading.value = true
  try {
    items.value = await listDocuments(selectedKnowledgeBaseId.value)
  } catch {
    ElMessage.error('加载文档列表失败')
  } finally {
    loading.value = false
  }
}

const allowedExtensions = ['.txt', '.pdf', '.docx', '.md']
const MAX_FILE_SIZE = 50 * 1024 * 1024

function handleFileChange(file, fileList) {
  pendingFiles.value = fileList
}

function handleFileRemove(file, fileList) {
  pendingFiles.value = fileList
}

async function startBatchUpload() {
  if (!selectedKnowledgeBaseId.value) {
    ElMessage.warning('请先选择知识库')
    return
  }
  const validFiles = []
  for (const entry of pendingFiles.value) {
    const lowerName = entry.name.toLowerCase()
    if (!allowedExtensions.some(ext => lowerName.endsWith(ext))) {
      ElMessage.warning(`文件 ${entry.name} 不支持，仅支持 TXT、PDF、DOCX、MD`)
      return
    }
    if (entry.size > MAX_FILE_SIZE) {
      ElMessage.warning(`文件 ${entry.name} 超过50MB`)
      return
    }
    validFiles.push(entry.raw)
  }
  if (!validFiles.length) {
    ElMessage.warning('请至少选择一个文件')
    return
  }
  uploading.value = true
  try {
    await uploadDocuments(selectedKnowledgeBaseId.value, validFiles)
    ElMessage.success('上传成功，文档处理中')
    pendingFiles.value = []
    if (uploadRef.value) {
      uploadRef.value.clearFiles()
    }
    await load()
  } finally {
    uploading.value = false
  }
}

function statusLabel(status) {
  const labels = {
    PROCESSING: '处理中',
    AVAILABLE: '可用',
    FAILED: '失败',
    DELETING: '删除中'
  }
  return labels[status] || status
}

function statusType(status) {
  const types = {
    PROCESSING: 'warning',
    AVAILABLE: 'success',
    FAILED: 'danger',
    DELETING: 'info'
  }
  return types[status] || 'info'
}

async function handleRetry(row) {
  retryingId.value = row.id
  try {
    await retryDocument(row.id)
    ElMessage.success('重试已发起，文档处理中')
    await load()
  } catch {
    ElMessage.error('重试失败')
  } finally {
    retryingId.value = null
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档「${row.filename}」吗？删除后该文档将立即退出问答检索，原文件与向量将在后台清理。`,
      '删除确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  deletingId.value = row.id
  try {
    await deleteDocument(row.id)
    ElMessage.success('删除已发起，文档清理中')
    await load()
  } catch {
    ElMessage.error('删除失败')
  } finally {
    deletingId.value = null
  }
}

loadKnowledgeBases()
</script>

<style scoped>
.header { display: flex; align-items: center; justify-content: space-between; }
.search-form { margin-bottom: 16px; }
.bailian-notice { margin-bottom: 16px; }
</style>
