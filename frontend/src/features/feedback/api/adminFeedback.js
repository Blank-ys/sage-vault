import request from '@/utils/request'

// 管理端接口，后端要求 sage:feedback:manage 权限；列表不返回问答正文。
export function listAdminFeedback({ status, pageNum, pageSize } = {}) {
  return request({
    url: '/ruoyi-kb-management/admin/feedback',
    method: 'get',
    params: { status, pageNum, pageSize }
  }).then(res => res.data)
}

// 详情包含用户已授权共享的问题与答案正文，仅在管理员显式打开时请求。
export function getAdminFeedbackDetail(id) {
  return request({
    url: `/ruoyi-kb-management/admin/feedback/${id}`,
    method: 'get'
  }).then(res => res.data)
}

export function resolveAdminFeedback(id, { status, adminNote }) {
  return request({
    url: `/ruoyi-kb-management/admin/feedback/${id}/status`,
    method: 'put',
    data: { status, adminNote }
  }).then(res => res.data)
}

export const FEEDBACK_STATUSES = [
  { value: 'PENDING', label: '待处理' },
  { value: 'RESOLVED', label: '已处理' }
]

// 与后端 QaRecordStatus 对齐；非 COMPLETED 说明答案可能是残缺的，需要提示管理员。
export const ANSWER_STATUS_LABELS = {
  STARTED: '生成中',
  REFUSED: '已拒答',
  COMPLETED: '已完成',
  STOPPED: '用户中途停止',
  UNFINISHED: '未完成'
}
