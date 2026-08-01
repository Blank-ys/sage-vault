# 07c — 流式停止与 Java→Python 尽力取消，已停止/未完成状态

**What to build:** 用户在流式回答期间停止生成时，Java 终止 SSE 流并通知 Python 尽力取消生成，已输出内容永久保存为已停止；系统中途失败时保存残缺内容为未完成；中断结果不计作成功回答。

**Blocked by:** 07b — 单用户串行化生成与问答状态机.

**Status:** ready-for-agent

- [ ] 停止 API：用户主动停止当前生成；Java 终止 SSE 流并释放 07b 的并发闸门。
- [ ] 契约新增 Java→Python 的取消/中止信号，Python 收到后尽力取消生成；连接断开不等同于业务取消。
- [ ] 用户主动停止时已输出内容永久保存为 `stopped`（已停止）。
- [ ] 系统中途失败时已输出残缺内容保存为 `incomplete`（未完成）。
- [ ] `stopped` 与 `incomplete` 均不计作成功回答，并可在历史中与完成/拒答区分展示。
- [ ] 遵循 `.agents/rules/async-and-rate-limit.md`：明确区分连接断开与业务取消。
- [ ] 验证：跨 Java/Python 契约两端验证 + 系统验收覆盖 停止→已停止 与 失败→未完成。
