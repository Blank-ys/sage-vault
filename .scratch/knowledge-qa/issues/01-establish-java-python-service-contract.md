# 01 — 建立 Java–Python 安全服务契约

**What to build:** 建立 Java 业务中台与 Python AI 服务之间可运行、可替换且受保护的系统边界，使后续问答、入库和可见范围变更都能通过 Nacos 发现服务，并以稳定契约发起任务和接收经过共享密钥鉴权的回调。

**Blocked by:** None — can start immediately.

**Status:** claimed

- [ ] Python AI 服务能够注册到 Nacos，Java 能通过服务发现调用它，不依赖写死的主机或端口。
- [ ] 授权声明、问答请求与响应、入库触发、可见范围重建触发及两类回调均有明确契约和边界测试。
- [ ] 入库和可见范围回调直连 Java，并拒绝共享密钥缺失或无效的请求。
- [ ] Python 暴露可替换的 `Retriever` 与 `LLMClient` 接缝，默认生成适配器可配置为 DeepSeek，契约测试不产生外部 LLM 请求。
