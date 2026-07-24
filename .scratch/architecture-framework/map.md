# Sage Vault 架构与代码框架路线图

## Destination

形成一组经确认、可直接用于起草 `docs/architecture.md` 与 `docs/code-framework.md` 的架构决策：前者说明当前底座、V1 目标模块、运行时关系与依赖方向；后者说明目录/包层次、代码准入规则，并为 `knowledge-qa` 的 12 张实施工单给出明确代码落点。

## Notes

- 当前综合规格见 [Sage Vault 架构与代码框架规格](spec.md)；地图只维护决策前沿和上下文索引，不复制规格正文。
- 本路线图只消除文档起草前的决策；决策齐备后再起草两份正式文档。
- 每次处理票据时使用 `grilling`、`domain-modeling` 和 `codebase-design`；领域术语以根目录 `CONTEXT.md` 为准，既有工程事实以 `backend/`、`frontend/`、`.scratch/knowledge-qa/spec.md` 和 `docs/adr/` 为准。
- 已确认 Java 新业务模块固定为 `backend/ruoyi-modules/ruoyi-kb-management`，不并入 `ruoyi-system`。
- 已确认 Python 根目录固定为与 `backend/` 同级的 `ai-modules/`，内部采用可扩展的多服务架构；V1 只有 RAG 服务。
- 文档区分“当前底座”与“V1 目标结构”，并采用深模块、小 interface 和明确 seam。

## Decisions so far

<!-- 决策在对应票据解决后以“要点 + 链接”追加于此，不在地图中重复详情。 -->

- [调研 Python 框架与版本兼容基线](issues/07-research-python-framework-version-baseline.md) — 已核实 Python 3.12、FastAPI/LangChain 稳定线、Milvus 2.4.23 与 PyMilvus 2.4.x 同代约束及 lockfile/镜像验证风险。
- [确定 ai-modules 多服务框架与 RAG 服务布局](issues/02-ai-modules-service-and-rag-layout.md) — `ai-modules/services/rag` 独立构建部署，采用 Python 3.12、FastAPI 0.139.x、LangChain 1.3.x 与 uv lock，框架类型不越过自有 application interface 和 adapters。

## Not yet specified

- 当 Java、跨进程接口和前端结构确定后，可能暴露配置命名、数据库迁移、契约生成或测试夹具需要独立顶层目录；在依赖关系确定前尚不能判断哪些值得成为稳定框架约定。
- 12 张实施工单可能需要按纵向能力拆分到多处，也可能应由单一深模块承接；需先确定各层 interface 和所有权，才能把“主落点、协作落点、验证落点”写成不误导实现者的索引。

## Out of scope

- 实现 `.scratch/knowledge-qa/issues/` 中的任何 V1 功能。
- 改变领域术语、产品验收标准、Java/Python 职责或百炼出网决策。
- 为尚无需求的未来 AI 服务预先设计业务 interface、数据模型或部署容量。
- 对现有 RuoYi 通用模块做与 Sage Vault 无关的重构。
