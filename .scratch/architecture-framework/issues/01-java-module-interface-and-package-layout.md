# 确定知识库管理 Java 模块的 interface 与包布局

Type: grilling
Status: claimed
Blocked by: none

## Question

`backend/ruoyi-modules/ruoyi-kb-management` 应如何拥有知识库、企业文档记录、异步任务、会话、问答记录和反馈等业务能力，并通过哪些最小 interface 与 `ruoyi-gateway`、`ruoyi-auth`、`ruoyi-system`、`ruoyi-file`、MySQL 和现有审计能力协作？请确定其内部包组织、依赖方向、哪些现有模块只作为平台依赖而不得承载 Sage Vault 业务，以及对应的测试 seam。

## Answer
