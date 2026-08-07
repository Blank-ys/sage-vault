# Sage Vault Agent 指南

Sage Vault 是基于 RuoYi Cloud 与 Vue 3 的企业知识库问答系统。Java 拥有业务状态与对外接口，Python 执行 RAG，浏览器只访问 Java。

本文件是跨工具 Agent 入口，只保存长期有效、代码里不容易直接推断、猜错会影响结果的规则。系统关系在 `docs/architecture.md`，代码落点在 `docs/code-framework.md`，完整技术栈与版本登记在 `docs/technology-stack.md`，更细的目录规则放在 `.agents/rules/` 需要时再读取。

## Safety rules

- Do not run destructive commands such as `rm -rf`, `git reset --hard`, or database migrations unless explicitly requested.
- Before modifying package dependencies, explain why the dependency is needed.
- Prefer read-only exploration before editing files.
- Show a short plan before large refactors.

## 开工前必读

规划或修改代码前必须按顺序读取：

1. `docs/architecture.md`：系统关系、运行时协作、所有权、依赖方向和 seam。
2. `docs/code-framework.md`：目标目录、目录准入、interface/adapter 落点、测试与部署资产、工单导航。
3. 涉及依赖、镜像、模型或运行 profile 时读取 `docs/technology-stack.md`。
4. 当前工单或规格：本地 issue 规则见 `docs/agents/issue-tracker.md`。
5. 与改动路径匹配的端规则：
   - 后端：`.agents/rules/backend.md`
   - 前端：`.agents/rules/frontend.md`
   - AI 模块：`.agents/rules/ai-modules.md`
   - 异步任务、流式取消或资源限流：`.agents/rules/async-and-rate-limit.md`

如果代码、工单、机器清单和架构文档冲突，立即停止并报告冲突，不得静默选择其中一份或绕过约束。

## 权威源与维护边界

- `docs/architecture.md` 唯一维护系统职责、运行时、数据所有权、依赖方向和关键 seam。
- `docs/code-framework.md` 唯一维护完整目标目录树、目录准入、测试/配置/部署落点和工单导航。
- `docs/technology-stack.md` 唯一维护完整技术栈、版本状态、运行 profile 和升级规则。
- `CONTEXT.md` 只维护业务术语;`docs/adr/` 记录需要保留取舍背景的架构决策。
- `.agents/rules/*.md` 只维护路径内编码规则与必跑验证，不复制目录树、版本表或架构决策。

架构事实变化时先修改拥有该事实的文档，再检查其他文档的链接和短摘要。能由测试、格式化、Hook 或 CI 强制的规则，应优先固化为自动检查。

## Commands

仓库尚未锁定 Maven CLI、Node.js 和 Yarn CLI;以下命令使用本机已安装工具，不构成发布环境版本证明;执行任何常用命令前需经过用户确认。

```bash
mvn -f backend/pom.xml test
mvn -f backend/pom.xml package -DskipTests
yarn --cwd frontend dev
yarn --cwd frontend build:prod
```

Python RAG（`ai-modules/services/rag`）、根级契约（`contracts/`）和系统验收（`system-tests/`）已落地；评测资产（`evaluation/`）与 V1 项目级唯一编排（`deploy/`）仍是目标结构，对应清单或脚本不存在时，不得虚构命令。现有 `backend/docker/docker-compose*.yml` 是待迁移入口，不得当作 V1 唯一 base 编排。

## Backend Rules

- 业务异常必须使用 `BusinessException(ErrorCode.XXX, "描述信息")`。
- 全局异常处理器返回 HTTP 200 + `R.error(code, message)`。
- 请求体优先用不可变 `record`，命名后缀使用 `XxxRequest` / `XxxResponse` / `XxxDTO` / `XxxEntity`。
- Entity 到 DTO/Response 的映射优先使用 MapStruct。
- 使用构造器注入，优先配合 Lombok `@RequiredArgsConstructor`。
- 代码无通配符导入、避免内联全限定类名。
- 日志使用 SLF4J 占位符，异常必须作为最后一个参数传入。

## Config And Data

- 配置集中在 `application.yml`、`.env` 和 `@ConfigurationProperties` 类中。
- API Key、数据库密码等敏感信息只放 `.env`，不得提交到 Git。
- 不要在 Service 中散落 `@Value`。

## 工作方式

- 修改前先从工单确定唯一 owner、必须跨越的公开 interface，以及能观察完整用户承诺的最高验证 seam。
- 代码、配置、测试和部署资产随 owner 就近放置；根级资产必须满足 `docs/code-framework.md` 的根级准入规则。
- 只实现当前工单所需的目标结构；不存在的 V1 目录不要提前创建空壳、通用层或占位抽象。
- 修改依赖、镜像或模型制品时，同时更新机器清单、`docs/technology-stack.md` 和相应验证证据。
- 保持现有 RuoYi 底座稳定；不要为统一目录外观重构与 Sage Vault 无关的模块。

## 技术栈

- Backend: RuoYi Cloud 3.6.8 / Java 17 / Spring Boot 4.0.6 / Spring Cloud 2025.1.1 / MyBatis。
- Frontend: Vue 3 / JavaScript / Vite / Element Plus / Pinia，代码在 `frontend/`。
- AI/RAG: Python 3.12 / FastAPI / `bge-m3` / Milvus / DashScope `qwen-plus`。
- Data & infrastructure: MySQL 8 / Redis 7 / MinIO / Nacos / Sentinel；异步任务由 Java MySQL 记录和模块内 scheduler 驱动。

完整依赖版本、锁定状态、基础设施 tag、模型制品、运行 profile 和升级规则见 `docs/technology-stack.md`。机器清单与该文档冲突时必须停止并同时修正，不得自行选择其一。

## 验证规则

- 验证改动涉及的最高公开 seam；模块测试用于定位，不能替代跨端或系统验收。
- 后端改动至少运行受影响 Maven 模块测试；公共能力或父 POM 改动运行 `mvn -f backend/pom.xml test`。
- 前端改动至少运行 `yarn --cwd frontend build:prod`；涉及交互时还需在浏览器验证真实页面行为。
- AI 改动按 `.agents/rules/ai-modules.md` 运行 Ruff、mypy、pytest 和受影响 profile；在清单尚未落地前明确报告未运行项。
- 跨 Java/Python wire contract 的改动必须同时验证根 schema/样例与两端 consumer/provider；不得只证明单端序列化成功。
- 无法运行命令时说明原因、已完成的替代检查和剩余风险，不得把未执行写成通过。

## 禁止事项

- 不要 `throw new RuntimeException(...)`，业务失败必须用 `BusinessException`。
- 不要直接返回 Entity 给前端。
- 不要把 `@Value` 散落在 Service 中。
- 不要在事务内调用 LLM、S3 或外部 HTTP。
- 不要同类内部调用 `@Transactional` 方法。
- 不要 `catch (Exception e) {}` 静默忽略。
- 不要循环调用 DB，优先批量查询或批量写入。
- 不要硬编码密钥、Token、数据库密码。
- 不要使用 `Executors.newXxxThreadPool()`，需要线程池时显式配置 `ThreadPoolExecutor`。
- 不得让浏览器或其他 RuoYi 服务绕过 Java 业务接口直连 Python、Milvus 或 Sage Vault 业务表。
- 不得在 Python、前端 store 或基础设施中复制 Java 拥有的业务状态机。
- 不得跨模块导入实现类、Mapper、持久化对象、adapter 或第三方 SDK 类型。
- 不得创建无 owner 的根级 `shared`、`common`、`utils`、通用 fixture 或配置副本。
- 不得提交凭据、真实敏感材料、未批准的 Nacos 配置、大体积模型或评测产物。
- 不得把连接断开等同于业务取消，也不得把异步投递成功等同于业务完成。
- 不得用候选版本、移动 tag、模型名或 resolver 成功冒充已验证且可复现的发布基线。

## Agent 工作流

### Issue tracker

问题作为本地 Markdown 文件存放在 `.scratch/<feature>/` 下。状态、依赖和操作规则见 `docs/agents/issue-tracker.md`。

### Domain docs

领域文档使用单一上下文布局：根 `CONTEXT.md` 加 `docs/adr/`。维护规则见 `docs/agents/domain.md`。

## 更多规则

- Java 后端：`.agents/rules/backend.md`
- Vue 前端：`.agents/rules/frontend.md`
- Python RAG：`.agents/rules/ai-modules.md`
- 异步、流式取消与资源限流：`.agents/rules/async-and-rate-limit.md`
