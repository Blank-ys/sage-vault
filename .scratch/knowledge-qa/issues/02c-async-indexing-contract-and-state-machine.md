# 02c — 跨端异步入库契约与状态机

**What to build:** 扩展 Java-Python 根契约，定义入库命令与回调；Java 拥有任务记录与状态裁决，Python 返回 202 并在完成后回调，重复、旧尝试和乱序回调被幂等收敛。

**Blocked by:** 02a — Java 企业文档上传、MinIO 原文件与业务记录；02b — Python RAG TXT 入库.

**Status:** resolved

- [x] 扩展 `contracts/java-python-rag/v1/`，新增入库命令、回调、相关事件 schema 和错误码
- [x] Java 新增任务记录持久化（taskId、attempt、status 等），由 `document` 业务能力拥有
- [x] Java 向 Python 派发签名入库命令，并携带可读取源文件的限时 URL
- [x] Python 返回 `202 Accepted`，处理完成后回调 Java
- [x] Java 按 `taskId`/`attempt` 幂等处理重复、旧尝试和乱序回调
- [x] 文档状态从 `PROCESSING` 正确转换为 `AVAILABLE` 或 `FAILED`
- [x] 根契约测试、Java callback 处理器测试、Python callback 测试通过
