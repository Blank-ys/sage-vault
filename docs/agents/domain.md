# Domain Docs

关于工程技能在探索代码库时应如何使用此仓库的领域文档。

## Before exploring, read these

- **`CONTEXT.md`** 位于仓库根目录，或
- **`CONTEXT-MAP.md`** 位于仓库根目录（如果存在）—— 它指向每个上下文的 `CONTEXT.md`。阅读与主题相关的每一个文件。
- **`docs/adr/`** — 阅读与你即将工作的领域相关的 ADR。在多上下文仓库中，也请检查 `src/<context>/docs/adr/` 以获取上下文特定的决策。

如果这些文件中的任何一个不存在，**请静默处理**。不要指出它们的缺失；不要建议立即创建它们。`/domain-modeling` 技能（通过 `/grill-with-docs` 和 `/improve-codebase-architecture` 访问）会在术语或决策真正需要解决时惰性地创建它们。

## File structure

单上下文仓库（大多数仓库）：

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多上下文仓库（根目录存在 `CONTEXT-MAP.md`）：

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← 系统级决策
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← 上下文特定决策
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## Use the glossary's vocabulary

当你的输出命名一个领域概念时（在问题标题、重构提案、假设、测试名称中），请使用 `CONTEXT.md` 中定义的术语。不要使用术语表明确避免的同义词。

如果你需要的概念尚未出现在术语表中，这是一个信号 — 要么你在使用项目没有使用的语言（请重新考虑），要么存在一个真正的空白（请记录下来供 `/domain-modeling` 处理）。

## Flag ADR conflicts

如果你的输出与现有 ADR 相矛盾，请明确指出来，而不是静默覆盖：

> _与 ADR-0007（事件溯源订单）相矛盾 — 但值得重新讨论因为……_
