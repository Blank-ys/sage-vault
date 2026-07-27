import request from '@/utils/request'

export function listAvailableKnowledgeBases() {
  return request({ url: '/ruoyi-kb-management/knowledge-bases/available', method: 'get' }).then(res => res.data)
}

export function listKnowledgeBases() {
  return request({ url: '/ruoyi-kb-management/knowledge-bases', method: 'get' }).then(res => res.data)
}

export function createKnowledgeBase(data) {
  return request({ url: '/ruoyi-kb-management/knowledge-bases', method: 'post', data }).then(res => res.data)
}

export function updateKnowledgeBase(id, data) {
  return request({ url: `/ruoyi-kb-management/knowledge-bases/${id}`, method: 'put', data }).then(res => res.data)
}
