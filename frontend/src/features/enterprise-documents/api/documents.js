import request from '@/utils/request'

export function uploadDocument(knowledgeBaseId, file) {
  const data = new FormData()
  data.append('knowledgeBaseId', knowledgeBaseId)
  data.append('file', file)
  return request({
    url: '/ruoyi-kb-management/documents',
    method: 'post',
    data,
    headers: { 'Content-Type': 'multipart/form-data' }
  }).then(res => res.data)
}

export function uploadDocuments(knowledgeBaseId, files) {
  const data = new FormData()
  data.append('knowledgeBaseId', knowledgeBaseId)
  files.forEach(file => data.append('files', file))
  return request({
    url: '/ruoyi-kb-management/documents/batch',
    method: 'post',
    data,
    headers: { 'Content-Type': 'multipart/form-data' }
  }).then(res => res.data)
}

export function listDocuments(knowledgeBaseId) {
  return request({
    url: '/ruoyi-kb-management/documents',
    method: 'get',
    params: { knowledgeBaseId }
  }).then(res => res.data)
}
