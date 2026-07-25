# Sage Vault 架构与代码框架路线图

## Destination

形成一组经确认、可直接用于起草 `docs/architecture.md` 与 `docs/code-framework.md` 的架构决策：前者说明当前底座、V1 目标模块、运行时关系与依赖方向；后者说明目录/包层次、代码准入规则，并为 `knowledge-qa` 的 12 张实施工单给出明确代码落点。

## Notes

- 当前综合规格见 [Sage Vault 架构与代码框架规格](spec.md)；地图只维护决策前沿和上下文索引，不复制规格正文。
- 本路线图只消除文档起草前的决策；决策齐备后再起草两份正式文档。
- 每次处理票据时使用 `grilling`、`domain-modeling` 和 `codebase-design`；领域术语以根目录 `CONTEXT.md` 为准，既有工程事实以 `backend/`、`frontend/`、`.scratch/knowledge-qa/spec.md` 和 `docs/adr/` 为准。
- 已确认 Java 新业务模块固定为与 `backend/ruoyi-modules` 同级的 `backend/ruoyi-kb-management`，不并入 `ruoyi-system`。
- 已确认 Python 根目录固定为与 `backend/` 同级的 `ai-modules/`，内部采用可扩展的多服务架构；V1 只有 RAG 服务。
- 文档区分“当前底座”与“V1 目标结构”，并采用深模块、小 interface 和明确 seam。
- Agent 代码规则按端存放在 `.agents/rules/`（如 `backend.md`、`ai-modules.md`）；根 `AGENTS.md` 按功能分类维护全局技术栈与版本，并作为规则地图链接架构文档和各端细则，不复制完整目录树或架构正文。

## Decisions so far

<!-- 决策在对应票据解决后以“要点 + 链接”追加于此，不在地图中重复详情。 -->

- [调研 Python 框架与版本兼容基线](issues/07-research-python-framework-version-baseline.md) — 已核实 Python 3.12、FastAPI/LangChain 稳定线、Milvus 2.4.23 与 PyMilvus 2.4.x 同代约束及 lockfile/镜像验证风险。
- [确定 ai-modules 多服务框架与 RAG 服务布局](issues/02-ai-modules-service-and-rag-layout.md) — `ai-modules/services/rag` 独立构建部署，采用 Python 3.12、FastAPI 0.139.x、LangChain 1.3.x 与 uv lock，框架类型不越过自有 application interface 和 adapters。
- [确定知识库管理 Java 模块的 interface 与包布局](issues/01-java-module-interface-and-package-layout.md) — 单一 Java 发布单元按业务能力组织并独占业务状态，以窄 application interface、平台 adapters、持久化任务和内容安全的审计/日志 seam 协作。
- [确定前端业务切片与后端 interface 落点](issues/03-frontend-feature-slices-and-interface.md) — 四个 feature 就近拥有页面、状态、Java adapters 与专用 UI，复用 RuoYi 平台，并以 `fetch` SSE 加显式取消区分停止与断流。
- [确定 Java-Python 契约所有权与运行时 seam](issues/04-java-python-contract-ownership.md) — 根级版本化 schema 约束双向 wire contract，异步采用至少一次幂等收敛，流与取消保持实例亲和，并以签名、数值错误码和双向契约测试隔离两端实现。
- [调研 bge-m3 revision 与 Torch 离线锁定基线](issues/09-research-bge-m3-revision-and-torch-lock.md) — 已核实 FlagEmbedding 1.4.0 与 Windows cu128 候选 wheel，并将模型 SHA、双平台锁定及 smoke 证明保留为发布前证据门禁。
- [确定 bge-m3 运行硬件与镜像基线](issues/08-bge-m3-runtime-and-image-profile.md) — Windows RTX 4060 试点采用离线 FlagEmbedding 单实例 GPU profile，模型/程序分发分离，并以有界优先队列、缓存 readiness 和双平台 smoke 门槛隔离未来推理 adapter。
- [确定配置、迁移、测试与运维代码的横切落点](issues/05-cross-cutting-code-placement.md) — 资产默认随 owner 就近放置，根级仅容纳有明确 owner 的跨发布单元契约、base 编排、评测与 smoke；业务 SQL 人工增量管理，Nacos 配置副本是否入 Git 由负责人裁定。
- [确定实施工单到代码的导航规则](issues/06-issue-to-code-wayfinding-rules.md) — 每张工单按权威状态确定唯一主落点，以公开 interface 标出协作端、以最高 seam 定验证；完整目录树与 12 张工单导航唯一归 `docs/code-framework.md`，根 `AGENTS.md` 强制新会话读取架构文档。

## Not yet specified

<!-- 当前没有尚不能精确表述的在途决策；配置、迁移、测试资产与实施工单导航已有对应开放票据。 -->

## Out of scope

- 实现 `.scratch/knowledge-qa/issues/` 中的任何 V1 功能。
- 改变领域术语、产品验收标准、Java/Python 职责或百炼出网决策。
- 为尚无需求的未来 AI 服务预先设计业务 interface、数据模型或部署容量。
- 对现有 RuoYi 通用模块做与 Sage Vault 无关的重构。
