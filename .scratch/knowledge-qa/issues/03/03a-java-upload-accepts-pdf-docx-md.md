# 03a — Java 上传入口接受 PDF/DOCX/MD

**What to build:** 知识管理员在同样的上传组件中可以选择 `.pdf`、`.docx`、`.md` 文件；Java 校验扩展名白名单并放行合法文件，原文件进入 MinIO 时带上正确的 MIME 类型，非法扩展名仍被明确拒绝。

**Blocked by:** None — 可在 02 基础上立即开始。

**Status:** ready-for-agent

- [ ] 前端 `accept` 与按钮文案支持 TXT/PDF/DOCX/MD 四种格式
- [ ] `DocumentFilename` 扩展名白名单新增 pdf、docx、md，同时保留 txt
- [ ] `DocumentServiceImpl` 按实际文件类型设置 MinIO content-type
- [ ] 非法扩展名仍返回明确错误，文件名唯一性校验逻辑不变
- [ ] 上传后文档记录状态为 `PROCESSING`，接口立即返回
- [ ] Java 模块测试、HTTP 上传接口测试通过；前端 `build:prod` 通过
