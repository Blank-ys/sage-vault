# 确定前端业务切片与后端 interface 落点

Type: grilling
Status: open
Blocked by: none

## Question

`frontend/src` 中的问答工作台、会话历史、知识库管理、企业文档管理和反馈处理应如何按业务能力组织，并如何复用现有 RuoYi 的路由、权限、状态、请求与通用 UI？请确定视图、业务状态、后端 interface 适配器和可复用 UI 的依赖方向，避免按页面复制逻辑或把 Sage Vault 业务塞进通用 `utils`、`components` 和全局 store。

## Answer
