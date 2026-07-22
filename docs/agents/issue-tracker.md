# Issue tracker: Local Markdown

此仓库的问题和规格说明（你可能将规格说明称为 PRD）作为 markdown 文件存放在 `.scratch/` 中。

## Conventions

- 每个功能一个目录：`.scratch/<feature-slug>/`
- 规格说明文件为 `.scratch/<feature-slug>/spec.md`
- 实现问题为每个工单一个文件，位于 `.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号 — 切勿使用单一的合并工单文件
- 分类状态记录在每个问题文件顶部附近的 `Status:` 行中（查看 `triage-labels.md` 了解角色字符串）
- 评论和对话历史附加在文件底部 `## Comments` 标题下

## When a skill says "publish to the issue tracker"

在 `.scratch/<feature-slug>/` 下创建新文件（必要时创建目录）。

## When a skill says "fetch the relevant ticket"

读取指定路径的文件。用户通常会直接传递路径或问题编号。

## Wayfinding operations

由 `/wayfinder` 使用。**地图**是一个文件，每个**子**工单对应一个文件。

- **地图**：`.scratch/<effort>/map.md` — 记录 / 决策目前 / 模糊地带 的主体。
- **子工单**：`.scratch/<effort>/issues/NN-<slug>.md`，从 `01` 开始编号，问题内容在正文中。`Type:` 行记录工单类型（`research`/`prototype`/`grilling`/`task`）；`Status:` 行记录 `claimed`/`resolved`。
- **阻塞**：顶部附近的 `Blocked by: NN, NN` 行。当工单列出的每个文件都为 `resolved` 时，该工单即解除阻塞。
- **前沿**：扫描 `.scratch/<effort>/issues/` 中打开、未阻塞且未认领的文件；按编号顺序取第一个。
- **认领**：设置 `Status: claimed` 并在工作前保存。
- **解决**：在 `## Answer` 标题下附加答案，设置 `Status: resolved`，然后将上下文指针（要点 + 链接）附加到 `map.md` 的地图"决策目前"部分。
