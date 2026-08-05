<template>
  <div class="management-page feedback-page">
    <management-page-header
      title="问答反馈处理"
      subtitle="按待处理/已处理查看用户反馈；详情正文仅在管理员显式打开时按后端授权返回。"
    />

    <div class="management-filters management-filters--inline">
      <el-radio-group v-model="status" @change="reload">
        <el-radio-button label="PENDING">待处理</el-radio-button>
        <el-radio-button label="RESOLVED">已处理</el-radio-button>
        <el-radio-button :label="null">全部</el-radio-button>
      </el-radio-group>
    </div>

    <el-table
      v-loading="loading"
      :data="items"
      class="management-table"
    >
      <el-table-column prop="id" label="编号" width="80" />
      <el-table-column label="问题类型" width="140">
        <template #default="scope">{{ categoryLabel(scope.row.category) }}</template>
      </el-table-column>
      <el-table-column prop="comment" label="用户说明" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'RESOLVED' ? 'success' : 'warning'">
            {{ statusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="提交时间" width="180" />
      <el-table-column label="操作" width="100">
        <template #default="scope">
          <el-button link type="primary" @click="openDetail(scope.row.id)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="pageNum"
      v-model:page-size="pageSize"
      class="management-pagination"
      layout="total, prev, pager, next"
      :total="total"
      @current-change="load"
    />

    <el-dialog v-model="detailVisible" title="反馈详情" width="720px">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="问题类型">{{ categoryLabel(detail.category) }}</el-descriptions-item>
        <el-descriptions-item label="用户说明">{{ detail.comment || '（未填写）' }}</el-descriptions-item>
        <el-descriptions-item label="用户提问">{{ detail.question }}</el-descriptions-item>
        <el-descriptions-item label="系统回答">
          <!-- 用户中途停止或生成失败时答案是残缺的，按存储原样展示以便排查 -->
          <el-alert
            v-if="detail.answerStatus !== 'COMPLETED'"
            class="partial-hint"
            type="warning"
            :closable="false"
            show-icon
            :title="`该回答状态为「${answerStatusLabel(detail.answerStatus)}」，内容可能不完整`"
          />
          <div class="answer">{{ detail.answer || '（无内容）' }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="请求 ID">{{ detail.requestId }}</el-descriptions-item>
        <el-descriptions-item label="检索诊断">
          <el-table v-if="detail.retrievalDiagnostics?.length" :data="detail.retrievalDiagnostics" size="small">
            <el-table-column prop="documentId" label="文档" />
            <el-table-column prop="chunkId" label="片段" />
            <el-table-column prop="score" label="分数" width="100" />
          </el-table>
          <span v-else class="muted">检索片段与阶段耗时的采集尚未接入</span>
        </el-descriptions-item>
        <el-descriptions-item label="内部备注">
          <el-input
            v-model="adminNote"
            type="textarea"
            maxlength="1000"
            show-word-limit
            placeholder="仅管理员可见，不会回传给提交反馈的用户"
          />
        </el-descriptions-item>
      </el-descriptions>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detail?.status === 'RESOLVED'" :loading="saving" @click="resolve('PENDING')">
          重新打开
        </el-button>
        <el-button v-else type="primary" :loading="saving" @click="resolve('RESOLVED')">
          标记已处理
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { FEEDBACK_CATEGORIES } from '../api/feedback'
import {
  ANSWER_STATUS_LABELS,
  FEEDBACK_STATUSES,
  getAdminFeedbackDetail,
  listAdminFeedback,
  resolveAdminFeedback
} from '../api/adminFeedback'

const loading = ref(false)
const saving = ref(false)
const detailVisible = ref(false)
const items = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const status = ref('PENDING')
const detail = ref(null)
const adminNote = ref('')

function categoryLabel(value) {
  return FEEDBACK_CATEGORIES.find(item => item.value === value)?.label ?? value
}

function statusLabel(value) {
  return FEEDBACK_STATUSES.find(item => item.value === value)?.label ?? value
}

function answerStatusLabel(value) {
  return ANSWER_STATUS_LABELS[value] ?? value
}

async function load() {
  loading.value = true
  try {
    const page = await listAdminFeedback({
      status: status.value ?? undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    items.value = page.items
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function reload() {
  pageNum.value = 1
  return load()
}

async function openDetail(id) {
  detail.value = await getAdminFeedbackDetail(id)
  adminNote.value = detail.value.adminNote ?? ''
  detailVisible.value = true
}

async function resolve(nextStatus) {
  saving.value = true
  try {
    detail.value = await resolveAdminFeedback(detail.value.id, {
      status: nextStatus,
      adminNote: adminNote.value
    })
    ElMessage.success(nextStatus === 'RESOLVED' ? '已标记为已处理' : '已重新打开')
    detailVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

load()
</script>

<style scoped>
/* 页面外壳、筛选区与分页由 .management-page / .management-filters--inline / .management-pagination 统一约束 */
.answer { white-space: pre-wrap; }
.partial-hint { margin-bottom: 8px; }
.muted { color: var(--el-text-color-secondary); }
</style>
