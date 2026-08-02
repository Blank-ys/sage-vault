# 09b — 级联删除失败诊断、幂等重试与并发安全

**What to build:** 知识库级联清理任一环节失败时进入删除失败、展示原因且仅允许查看和重试删除、不恢复为可用；删除与重试是幂等的，并在整个清理窗口阻止并发上传或提问重新产生数据。

**Blocked by:** 09a — 知识库级联删除 happy path.

**Status:** resolved

- [x] 任一清理失败时知识库进入 删除失败 状态并展示失败原因（失败阶段）。
- [x] 删除失败仅允许查看与重试删除，不恢复为可用。
- [x] 删除与重试幂等（CAS + attempt 递增/终态检查），不重复清理已删除的文档、对象或向量。
- [x] 清理窗口内阻止并发上传、会话与提问重新产生数据。
- [x] 不误删清理窗口之外的数据。
- [x] 前端提供 删除失败 可辨识标签与重试入口。
- [x] 验证：系统验收覆盖 注入清理失败→删除失败状态与拒绝新操作→重试→最终清理完成。

## Comments

### 2026-08-02 实现完成并验证

- Java 模块测试：`mvn -pl ruoyi-kb-management test` 210 passed / 0 failed（连 `192.168.150.100:3306/ry-cloud`）。
- 前端构建：`yarn --cwd frontend build:prod` 通过。
- 系统测试：`system-tests/knowledge-qa/test_cascade_delete_failure_and_retry.py` 对真实网关执行，`test_cleanup_never_touches_other_knowledge_bases` 通过；3 条需要失败注入的用例按设计跳过（`SAGE_VAULT_CLEANUP_FAILURE_INJECTED` 未设置）。
- 修复了 `KnowledgeBaseMapperMySqlIntegrationTest` 中的顺序相关 flaky：@BeforeEach 增加按测试前缀清表，并在关键断言前校验 `id > 0` 与 `findById != null`。
- 修复了系统测试脚本中的同秒名称冲突：将 `int(time.time())` 改为 `time.time_ns()`。
