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

// 删除是异步级联的：返回时知识库进入 DELETING 并立刻拒绝新上传与提问，清理在后台继续
export function deleteKnowledgeBase(id) {
  return request({ url: `/ruoyi-kb-management/knowledge-bases/${id}`, method: 'delete' }).then(res => res.data)
}
