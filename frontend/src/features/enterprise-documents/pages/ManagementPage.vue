<template>
  <div class="app-container">
    <el-card>
      <template #header>
        <div class="header">
          <span>企业文档</span>
        </div>
      </template>

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
            :show-file-list="false"
            :on-change="handleFileChange"
            accept=".txt"
          >
            <el-button type="primary" :loading="uploading" :disabled="!selectedKnowledgeBaseId">上传 TXT</el-button>
          </el-upload>
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
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { listKnowledgeBases } from '../../knowledge-bases'
import { listDocuments, uploadDocument } from '../api/documents'

const loading = ref(false)
const uploading = ref(false)
const selectedKnowledgeBaseId = ref(null)
const knowledgeBases = ref([])
const items = ref([])
const uploadRef = ref(null)

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

async function handleFileChange(file) {
  if (!selectedKnowledgeBaseId.value) {
    ElMessage.warning('请先选择知识库')
    return
  }
  if (!file.name.toLowerCase().endsWith('.txt')) {
    ElMessage.warning('仅支持上传 TXT 文件')
    return
  }
  uploading.value = true
  try {
    await uploadDocument(selectedKnowledgeBaseId.value, file.raw)
    ElMessage.success('上传成功，文档处理中')
    await load()
  } finally {
    uploading.value = false
    if (uploadRef.value) {
      uploadRef.value.clearFiles()
    }
  }
}

function statusLabel(status) {
  const labels = {
    PROCESSING: '处理中',
    AVAILABLE: '可用',
    FAILED: '失败'
  }
  return labels[status] || status
}

function statusType(status) {
  const types = {
    PROCESSING: 'warning',
    AVAILABLE: 'success',
    FAILED: 'danger'
  }
  return types[status] || 'info'
}

loadKnowledgeBases()
</script>

<style scoped>
.header { display: flex; align-items: center; justify-content: space-between; }
.search-form { margin-bottom: 16px; }
</style>
