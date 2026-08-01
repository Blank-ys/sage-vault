import request from '@/utils/request'
import { getToken } from '@/utils/auth'

export function createConversation(knowledgeBaseId) {
  return request({ url: '/ruoyi-kb-management/conversations', method: 'post', data: { knowledgeBaseId } }).then(res => res.data)
}

export function listConversations() {
  return request({ url: '/ruoyi-kb-management/conversations', method: 'get' }).then(res => res.data)
}

export function listQuestions(conversationId) {
  return request({ url: `/ruoyi-kb-management/conversations/${conversationId}/questions`, method: 'get' }).then(res => res.data)
}

export function renameConversation(conversationId, title) {
  return request({ url: `/ruoyi-kb-management/conversations/${conversationId}/title`, method: 'put', data: { title } }).then(res => res.data)
}

export function deleteConversation(conversationId) {
  return request({ url: `/ruoyi-kb-management/conversations/${conversationId}`, method: 'delete' })
}

export async function askQuestion(conversationId, question, onEvent, signal) {
  const response = await fetch(`${import.meta.env.VITE_APP_BASE_API}/ruoyi-kb-management/conversations/${conversationId}/questions`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, requestId: crypto.randomUUID() }),
    signal
  })
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok || !response.body) throw new Error('问答服务连接失败')
  if (!contentType.includes('text/event-stream')) {
    const result = await response.json().catch(() => null)
    throw new Error(result?.msg || '问答服务连接失败')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split('\n\n')
    buffer = frames.pop()
    frames.forEach(frame => {
      const event = frame.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim()
      const data = frame.split('\n').find(line => line.startsWith('data:'))?.slice(5).trim()
      if (event && data) onEvent({ type: event, ...JSON.parse(data) })
    })
  }
}
