# 07 — 完善会话、历史与流式中断

**What to build:** 普通用户能够用会话组织独立问答、查看和管理永久历史，并在流式回答期间停止生成；系统清楚区分完整、已停止和未完成结果。

**Blocked by:** 02 — 上传并问答一篇 TXT 企业文档.

**Status:** resolved

- [x] 用户可新建绑定单个知识库的会话；首个问题成为默认标题，标题可修改，一个会话可保存多条按时间展示的独立问答记录。（由 07a 实现）
- [x] 历史消息不参与后续检索、问题改写或模型上下文，每个问题始终只检索会话绑定的知识库。（由 07a 实现，无状态单轮检索由 `RagAnswerPort.answer` 签名结构性保证）
- [x] 每个用户同一时间只能有一个正在生成的回答；第二次提问被拒绝并提示等待或停止当前回答，不同用户仍可并发。（由 07b 实现，`CONCERSATION_CONCURRENCY_CONFLICT 410016`）
- [x] 用户停止生成时 Java 终止流并通知 Python 尽力取消生成，已输出内容永久保存为已停止；系统中途失败时保存残缺内容为未完成。（由 07c 实现，`STOPPED`/`UNFINISHED`）
- [x] 完成、拒答、已停止和未完成均持久化正确状态；SSE 至少覆盖 `started`、`delta`、`completed`、`refused`、`error`，且中断结果不计作成功回答。（由 07b+07c 实现）
- [x] 用户只能查看和删除自己的问答记录或会话；删除会话级联删除其中问答与反馈正文，但允许保留不含正文的操作审计。（由 07a 实现）

## Comments

### 2026-07-31 Split into tracer-bullet tickets

This ticket has been split into the following sub-tickets to keep each implementation window focused:

- [07a — 会话组织、永久历史与所有权级联删除](07a-conversations-history-and-ownership.md)
- [07b — 单用户串行化生成与问答状态机、SSE 事件](07b-single-user-serialization-and-answer-state-machine.md)
- [07c — 流式停止与 Java→Python 尽力取消，已停止/未完成状态](07c-stream-stop-and-best-effort-cancel.md)

Do not implement this ticket directly; pick up the sub-tickets in dependency order.
