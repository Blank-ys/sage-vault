# 确定配置、迁移、测试与运维代码的横切落点

Type: grilling
Status: resolved
Blocked by: 01, 02, 03, 04, 08

## Question

在已确定的 Java、Python、前端和跨进程 seam 之上，数据库迁移与种子数据、Nacos/环境配置、Docker 编排、契约测试、Milvus 集成测试、质量评测、日志脱敏验证和部署冒烟清单分别应归哪个模块所有？请形成“代码随所有者就近放置”与“允许进入仓库级目录”的明确准入规则，避免横切资产散落或形成无主的共享目录。

## Answer

### 总准入规则

- 代码、配置、测试和运维资产默认随拥有其事实或实现的发布单元就近放置。只有同时跨至少两个发布单元、存在明确 owner、无法合理归入单端且只通过公开 interface 工作的资产，才允许进入仓库级目录。
- 每个根级横切目录必须有 README，说明 owner、消费者、允许与禁止内容、敏感数据规则及验证命令。禁止创建无 owner 的根级 `shared/`、`common/`、`utils/`、通用 fixtures 或配置副本，也不得借横切目录绕过模块 interface。
- 本决策只确定目标落点与准入规则，不在路线图阶段搬迁文件或实现测试。现有资产在对应实施工作开始时迁移，迁移后删除旧权威入口，禁止长期保留双份真相。

### 数据库 schema 与种子数据

- V1 暂不引入 Flyway。Sage Vault 业务 SQL 的唯一权威放在 `backend/ruoyi-kb-management/sql/`，采用人工执行的不可变增量编号脚本：`001_schema.sql` 创建业务表、索引和约束，`002_seed.sql` 写入菜单、权限等可重复执行的种子数据，后续变更只追加新编号，已发布脚本不得改写。
- `backend/sql/` 继续承载 RuoYi 底座/首次安装资产，可提供指向业务脚本的入口说明，但不得复制 Sage Vault schema 或种子内容。部署清单记录执行顺序，Java MySQL 集成测试复用同一 schema 脚本，禁止维护测试专用建表 SQL。

### 运行配置与 Nacos 副本

- Java 的 `bootstrap.yml` 随 `ruoyi-kb-management` 存放，只承载服务名、端口和 Nacos 导入规则；Python 的默认值、类型和校验随 `ai-modules/services/rag` 的 bootstrap 实现存放。可提交的字段说明与非敏感模板随各发布单元就近维护。
- 实际环境值以 Nacos/环境变量为运行来源。密码、密钥、真实环境地址和模型本地路径不得写入提交的模板；同一配置项只有一个运行时 owner，不在根级或两端复制环境事实。
- Nacos 中各 Data ID 的实际配置在 `deploy/dev/nacos-config/` 保留一份本地副本，文件应标明 Data ID、Group、环境以及是否含秘密。是否将某份副本提交 Git 由项目负责人逐份裁定；未获批准的文件必须保持未跟踪/忽略状态，不得因自动化流程擅自提交。

### 项目 base 组件编排

- `deploy/dev/docker-compose-base.yml` 是项目 base 组件的唯一启动编排，统一拥有 MySQL、Redis、Nacos、Sentinel、etcd、MinIO、Milvus 和 Attu 等开发/试点基础设施组合；Windows 原生前端、Java、Python 与 Ubuntu VM 中间件的启动说明也归 `deploy/dev/`。
- 实施迁移时合并并退役 `backend/docker/docker-compose-base.yml` 与 `backend/docker/docker-compose-milvus.yml`，删除旧文件，避免多个权威入口。现有 MySQL、Redis、Nacos 服务自身的辅助配置可暂留 `backend/docker/`，由新 compose 以明确相对路径引用；Nacos Data ID 副本仍按上一节归 `deploy/dev/nacos-config/`。
- Java 与 Python 的 Dockerfile 随各自发布单元存放。`deploy/` 只负责跨发布单元环境组装、非敏感样例、启动说明和 smoke 编排，不承载业务代码、单端私有配置或 adapter 实现。

### Java-Python 契约验证

- `contracts/java-python-rag/v1/` 保存共同的 schema、错误注册表和成功/失败/边界样例；`contracts/java-python-rag/tests/` 只做语言无关的 schema、样例与兼容性校验。
- Java consumer/provider 测试归 `ruoyi-kb-management` 测试目录，Python consumer/provider 测试归 `ai-modules/services/rag/tests/contract/`。两端 CI 共同引用根级契约，不复制 schema 或样例，也不得建立绕过正式契约的测试专用 interface。

### Milvus 与模块测试

- 真实 Milvus 集成测试由 Python RAG 模块独占，放在 `ai-modules/services/rag/tests/integration/milvus/`，并通过项目自有入库/检索 interface 验证 collection schema、`knowledgeBaseId` 强制过滤、整篇发布、即时排除和幂等删除；夹具随该测试目录存放。
- Java 和根级系统验收只通过 Python 契约观察最终行为，不直连 Milvus。测试所需基础设施由 `deploy/dev/docker-compose-base.yml` 提供，具体 adapter 自测仍随拥有它的发布单元存放。

### 质量评测与日志脱敏

- 根级 `evaluation/knowledge-qa/` 由试点负责人拥有：`datasets/` 保存脱敏且获授权的企业文档夹具、问题和人工标注，`configs/` 保存参数组合，`runner/` 只通过 Java 对外 HTTP/SSE interface 执行端到端评测，`reports/` 只提交模板和小型基线摘要。真实敏感材料、大体积运行产物和秘密不得进入 Git。
- Python 内部检索调优工具可随 RAG 模块存放，但 V1 正式质量、拒答与性能结论必须从上述外部 interface 得出，不能用越过系统 seam 的内部测试替代。
- 日志脱敏采用“两端就近测试 + 根级端到端泄漏扫描”：Java 日志和审计白名单测试归 `ruoyi-kb-management`，Python 字段与异常清洗测试归 RAG 服务；根级评测使用唯一探针字符串贯穿上传、检索、问答、失败和取消，并扫描汇集日志证明正文未泄漏。
- 允许字段清单由各端规则与代码常量拥有；根级测试只保存禁止内容类别和探针，不复制实现字段表，也不创建跨语言共享 logging 模块。

### 部署冒烟

- `deploy/smoke/` 保存跨系统冒烟入口、非敏感测试数据和结果汇总。自动检查覆盖 Nacos 注册、MySQL、MinIO、Milvus、Java/Python readiness、离线 `bge-m3` profile、异步入库/清理补偿和关联 ID；百炼真实连通、流式回答观感及运维日志查看保留为人工清单。
- 根级 smoke 只调用公开业务 interface 或基础设施健康 interface，不直连私有表或内部实现伪造成功；具体 adapter 自测仍由各发布单元拥有。环境地址与凭据只从环境变量/Nacos 注入，脚本、清单和报告不得保存秘密。
