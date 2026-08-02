import request from '@/utils/request'

// 反馈会把问题与答案共享给管理员，consentToShare 必须由用户在界面上显式勾选后才提交。
export function submitFeedback(qaId, { category, comment, consentToShare }) {
  return request({
    url: `/ruoyi-kb-management/qa/${qaId}/feedback`,
    method: 'post',
    data: { category, comment, consentToShare }
  }).then(res => res.data)
}

export const FEEDBACK_CATEGORIES = [
  { value: 'WRONG_ANSWER', label: '答案错误' },
  { value: 'NO_ANSWER_FOUND', label: '没有找到答案' },
  { value: 'INCOMPLETE_ANSWER', label: '答案不完整' },
  { value: 'OTHER', label: '其他问题' }
]
