# 02a — Java 企业文档上传、MinIO 原文件与业务记录

**What to build:** 在 `ruoyi-kb-management` 内新增 `document` 业务能力，使知识管理员能够向知识库上传一篇 TXT 文件；原文件进入 MinIO，业务记录进入 MySQL，接口立即返回且文档显示为处理中。

**Blocked by:** 01 — 打通空知识库问答细线.

**Status:** resolved

- [x] 新增 `sv_enterprise_document` 表（含 id、kb_id、filename、normalized_name、status、object_key、size、error_message、created_at、updated_at）
- [x] 在 `document` 业务能力下实现 `Entity`/`Mapper`/`Service`/`Controller`
- [x] 实现模块自有 MinIO adapter，不经过 `ruoyi-file`
- [x] 上传接口仅接受 TXT 文件，同知识库内文件名不区分大小写唯一；冲突时拒绝整个请求并返回明确错误
- [x] 验证通过后文档记录状态为 `PROCESSING`，接口立即返回
- [x] 前端 `enterprise-documents` feature 提供上传组件和文档列表，显示 `PROCESSING`/`AVAILABLE`/`FAILED` 状态
- [x] Java 模块测试、HTTP 上传接口测试、真实 MinIO 集成测试通过；前端 `build:prod` 通过
