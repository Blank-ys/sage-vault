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
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column label="操作" width="100">
          <template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button></template>
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
import { ElMessage } from 'element-plus'
import { createKnowledgeBase, listKnowledgeBases, updateKnowledgeBase } from '../api/knowledgeBases'

const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const items = ref([])
const form = reactive({ id: null, name: '', description: '' })

async function load() {
  loading.value = true
  try { items.value = await listKnowledgeBases() } finally { loading.value = false }
}

function openCreate() { Object.assign(form, { id: null, name: '', description: '' }); visible.value = true }
function openEdit(item) { Object.assign(form, item); visible.value = true }
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
</style>
