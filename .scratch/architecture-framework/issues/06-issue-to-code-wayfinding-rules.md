# 确定实施工单到代码的导航规则

Type: grilling
Status: resolved
Blocked by: 01, 02, 03, 04, 05

## Question

如何把 `.scratch/knowledge-qa/issues/` 的 12 张实施工单映射到已确定的模块与目录，使每张工单都有唯一主落点、必要的协作落点和验证落点，同时让新增 issue 能通过一组稳定规则自行判断归属？请确定 `docs/architecture.md` 与 `docs/code-framework.md` 各自承载哪些内容、如何避免重复，以及文档中 issue 索引的粒度和维护方式。

## Answer

### 导航模型

- 每张实施工单必须有且只有一个**主落点**：负责协调工单、拥有完成定义中最关键权威状态或不变量的模块。主落点按所有权而非改动文件数或代码行数判断。
- **协作落点**只列完成工单确实需要修改的其他 feature、application 模块、adapter、跨进程契约或部署资产；主落点不表示协作端可以缺席。
- **验证落点**与实现落点分开记录，选择能观察完整用户承诺的最高 seam；模块级测试用于定位，不能替代工单要求的系统级证明。
- 如果新工单无法选出唯一主落点，先按独立不变量拆分工单；确实不可拆时显式指定一个协调模块。不得用根级 `shared`、`common` 或测试目录充当无 owner 的主落点。

### 12 张实施工单导航

| 实施工单 | 主落点 | 必要协作落点 | 验证落点 |
| --- | --- | --- | --- |
| [打通空知识库问答细线](../../knowledge-qa/issues/01-empty-knowledge-base-qa-tracer.md) | `backend/ruoyi-kb-management` 的知识库、会话与问答能力 | `frontend/src/features/{knowledge-bases,conversations}`；Python `application/answering`、HTTP/Nacos adapters；根级 Java-Python 契约 | 浏览器到 Java 的 HTTP/SSE 系统验收；两端契约测试 |
| [上传并问答一篇 TXT 企业文档](../../knowledge-qa/issues/02-upload-and-answer-txt-document.md) | `backend/ruoyi-kb-management` 的企业文档任务、状态与发布裁决 | 前端 `enterprise-documents`/`conversations`；Python `application/{indexing,answering}` 及 MinIO、`bge_m3`、Milvus adapters；根级契约 | 上传至回答的系统验收；Python 真实 Milvus 集成测试；两端契约测试 |
| [扩展 PDF、DOCX、MD 企业文档解析](../../knowledge-qa/issues/03-support-pdf-docx-markdown.md) | `ai-modules/services/rag` 的 `application/indexing` 与内部解析实现 | Java 上传格式/大小校验和失败映射；前端 `enterprise-documents` 格式限制与失败展示 | Python 解析器集成测试；浏览器到 Java 的上传成功/失败系统验收 |
| [实现批量上传与同名原子校验](../../knowledge-qa/issues/04-batch-upload-and-name-validation.md) | `backend/ruoyi-kb-management` 的企业文档能力 | 前端 `enterprise-documents` 批量选择、冲突展示与出网提示；Python 只复用既有单文档命令 | Java application/MySQL 集成测试；浏览器到 Java 的批量上传系统验收 |
| [完成文档失败重试与原子发布](../../knowledge-qa/issues/05-retry-and-atomic-document-publication.md) | `backend/ruoyi-kb-management` 的企业文档任务状态机与重试裁决 | 前端 `enterprise-documents`；Python `application/indexing`、临时产物与 Milvus 发布/清理；根级契约 | Java 状态/幂等/乱序测试；Python Milvus 集成测试；失败注入系统验收 |
| [完成文档删除与名称释放](../../knowledge-qa/issues/06-delete-document-and-release-name.md) | `backend/ruoyi-kb-management` 的企业文档删除与名称占用规则 | 前端 `enterprise-documents`；Python `application/cleanup` 与 Milvus 清理；Java MinIO adapter；根级契约 | Java 状态/MySQL/MinIO 集成测试；Python Milvus 集成测试；立即排除与最终清理系统验收 |
| [完善会话、历史与流式中断](../../knowledge-qa/issues/07-conversations-history-and-stream-control.md) | `backend/ruoyi-kb-management` 的会话、问答记录、单活跃回答与终态裁决 | 前端 `conversations` store/adapter；Python `application/answering`、生成/取消 adapters；根级 SSE/取消契约 | 浏览器到 Java 的历史、隔离、完成/拒答/停止/断流系统验收；两端流契约测试 |
| [建立用户反馈隐私闭环](../../knowledge-qa/issues/08-user-feedback-privacy-loop.md) | `backend/ruoyi-kb-management` 的反馈能力及正文授权规则 | 前端 `feedback` 与 `conversations`；Java 审计 adapter | Java application/MySQL 权限测试；浏览器到 Java 的提交同意、管理员可见性与删除系统验收 |
| [实现知识库级联删除](../../knowledge-qa/issues/09-cascade-delete-knowledge-base.md) | `backend/ruoyi-kb-management` 的知识库能力与级联协调 | 前端 `knowledge-bases`、`enterprise-documents`、`conversations`；Java MinIO adapter；Python `application/cleanup` 与 Milvus adapter；根级契约 | Java 恢复/幂等/MySQL/MinIO 测试；Python Milvus 测试；关闭新操作、清理和历史保留系统验收 |
| [接入百炼 qwen-plus 生成适配器](../../knowledge-qa/issues/10-bailian-qwen-generation-adapter.md) | `ai-modules/services/rag` 的 `adapters/dashscope` | Python `application/answering` 与 bootstrap 配置；前端复用既有出网提示；`deploy/smoke` 人工供应商检查 | 生成 port 的确定性假 adapter 自动化测试；部署后真实百炼人工流式 smoke |
| [守住角色、审计与安全日志边界](../../knowledge-qa/issues/11-roles-audit-and-safe-logging.md) | `backend/ruoyi-kb-management` 的权限裁决、`ManagementAudit` 与安全日志规则 | 前端四个 feature 的菜单/按钮可见性；Python 安全日志；RuoYi 平台 adapters；根级泄漏探针 | Java/前端权限与审计系统验收；两端日志单测；`evaluation/knowledge-qa` 端到端日志泄漏扫描 |
| [建立 V1 质量、容量与性能验收](../../knowledge-qa/issues/12-v1-quality-capacity-acceptance.md) | 根级 `evaluation/knowledge-qa`，owner 为试点负责人 | `deploy/smoke`；Python Milvus 集成测试；Java/Python/前端必要可观测性 | 只经 Java 对外 HTTP/SSE interface 的质量、隔离、容量、时延与部署验收；模块测试仅辅助诊断 |

上述导航只固定模块、稳定目录和 seam，不预先指定易变类名、函数名或文件清单。工单正文仍是需求、阻塞与验收 checklist 的唯一权威，导航表不得复制这些内容。

### 文档单一职责

- `docs/architecture.md` 是系统架构的权威：描述当前 RuoYi 底座、V1 目标系统、运行时关系、模块职责、数据所有权、依赖方向、关键 interface/seam 和 ADR 链接。只保留理解关系所需的高层模块图，不保存完整代码目录树或逐工单落点。
- `docs/code-framework.md` 是代码结构与导航的权威：保存完整目标目录树、Java 包结构、Python 模块结构、前端 feature 结构、根级 `contracts/`、`deploy/`、`evaluation/`，以及每个目录的准入/禁止内容、interface/adapter 位置、配置/SQL/测试落点和本工单的逐项导航表。
- 两份文档通过链接互相引用，不复制段落。架构事实变化先更新拥有该事实的唯一文档，再检查另一份文档的链接或必要的一句摘要是否仍成立。

### 新会话强制导航

- 根 `AGENTS.md` 是新会话的强制入口：它按功能分类维护全局技术栈、版本状态和升级规则，并要求规划或修改代码前先读 `docs/architecture.md` 与 `docs/code-framework.md`；修改特定端时再读 `.agents/rules/backend.md`、`frontend.md` 或 `ai-modules.md`。除全局技术栈版本外，它不复制架构正文。
- 根规则还必须要求：若代码、工单与架构文档冲突，停止并明确报告冲突，不得静默绕过。完整目录树和归属算法不复制到 `AGENTS.md`。
- 各端规则保存端内具体编码、依赖与必跑验证约束，并链接 `docs/code-framework.md` 对应章节；不得另存一套目录结构。

### 新工单归属与维护

新增或调整实施工单时依次执行：

1. 找出最关键的权威状态或不变量，其 owner 成为唯一主落点。
2. 列出必须跨越的公开 interface，只有 interface 两侧成为协作落点。
3. 选择能观察完整用户承诺的最高 seam 作为验证落点。
4. 无法确定唯一 owner 时先拆分工单，或显式指定协调模块。
5. 只有真正跨多个发布单元且有明确 owner 的资产才能进入根级目录，并继续遵循横切资产准入规则。

`docs/code-framework.md` 的导航表保持一行一工单，列出工单名称/链接、唯一主落点、必要协作落点和验证落点。新增、拆分、改名或改变主落点时，同一变更必须更新该表，代码评审必须检查；工单完成后不删除导航行，只更新状态或稳定实现入口链接，以保留架构追踪。
