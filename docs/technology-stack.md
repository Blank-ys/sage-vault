# Sage Vault 技术栈与版本

本文是 Sage Vault 完整技术栈、版本状态、运行 profile 和升级规则的唯一权威。根 [AGENTS.md](../AGENTS.md#技术栈) 只保留高频摘要；系统职责和运行关系见 [系统架构](architecture.md)。

状态分为：**当前锁定**（仓库清单已固定）、**V1 目标**（允许范围已确定，精确版本待 lock 验证）、**候选待验证**和**未锁定**。机器真相是 Maven 清单、`frontend/package.json` 与 `yarn.lock`、未来 Python `pyproject.toml` 与 `uv.lock`，以及项目 compose 的镜像 tag/digest。若机器清单与本文冲突，停止并同时修正，不得自行选择其一。

## Backend: Java、Spring 与 RuoYi

| 功能 | 技术与版本 | 状态 |
| --- | --- | --- |
| 微服务底座 | RuoYi Cloud `3.6.8` | 当前锁定 |
| 语言 | Java `17` | 当前锁定 |
| 应用框架 | Spring Boot `4.0.6` | 当前锁定 |
| 微服务框架 | Spring Cloud `2025.1.1` | 当前锁定 |
| Nacos/Sentinel 集成 | Spring Cloud Alibaba `2025.1.0.0` | 当前锁定 |
| 服务监控 | Spring Boot Admin `4.0.4` | 当前锁定 |
| ORM | MyBatis Spring Boot `4.0.1` | 当前锁定 |
| 数据源 | Dynamic Datasource `4.5.0`、Druid `1.2.28` | 当前锁定 |
| 分页 | PageHelper `4.1.0` | 当前锁定 |
| OpenAPI | Springdoc `3.0.3` | 当前锁定 |
| 对象存储客户端 | MinIO Java SDK `8.2.2` | 当前锁定 |
| JSON | Fastjson2 `2.0.62` | 当前锁定 |
| 文档与 IO 既有依赖 | Apache POI `4.1.2`、Commons IO `2.22.0` | 当前锁定；POI 不承担 Python RAG 解析 |
| 线程上下文 | Transmittable Thread Local `2.14.5` | 当前锁定 |
| 认证既有依赖 | JJWT `0.9.1` | 当前锁定 |
| 验证码 | Kaptcha `2.3.3` | 当前锁定 |
| 代码生成 | Velocity `2.3` | 当前锁定 |
| Maven 编译插件 | `3.11.0` | 当前锁定 |
| Maven CLI | 未指定 | 未锁定；应通过 Wrapper 或构建环境固定 |
| Java 基础镜像 | `openjdk:17` | 未锁定；tag 可移动，发布前固定发行版和 digest |

MySQL Connector、Nacos client、Sentinel、Actuator 等由 Spring/RuoYi dependency management 解析；发布时必须保存 Maven 精确依赖图，不能从本表推测。

## Database、Cache 与 Storage

| 功能 | 技术与版本 | 状态 |
| --- | --- | --- |
| 业务数据库 | MySQL `8.0.45` | V1 base 编排目标 |
| 缓存与认证支撑 | Redis `7` | 主版本锁定；小版本/digest 未锁定 |
| 企业文档对象存储 | MinIO Server `RELEASE.2024-12-18T13-15-44Z` | 当前锁定 |
| 向量数据库 | Milvus `v2.4.23` | 当前锁定；单 Collection |
| Milvus 元数据 | etcd `v3.5.18` | 当前锁定 |
| Milvus 管理界面 | Attu `v2.4.12` | 当前锁定 |
| 向量规格 | `bge-m3` 稠密向量 `1024` 维、L2 归一化、Milvus 稠密检索 | 维度/归一化已确认；距离类型待评测裁定；`knowledgeBaseId` 强制过滤 |

V1 不使用 PostgreSQL、pgvector、Redisson 或 Redis Stream。异步任务由 Java MySQL 持久化记录和模块内 scheduler 驱动。

## AI/RAG: Python、模型与解析

| 功能 | 技术与版本 | 状态 |
| --- | --- | --- |
| Python | `>=3.12,<3.13` | V1 目标；试点使用 Python 3.12 |
| HTTP/SSE | FastAPI `>=0.139,<0.140`、Uvicorn `>=0.51,<0.52` | V1 目标 |
| RAG 编排 | LangChain `>=1.3.14,<2` | V1 目标；只用于内部实现 |
| 配置/transport model | Pydantic `>=2.13,<3`、pydantic-settings `>=2.14,<3` | V1 目标 |
| Milvus 客户端 | PyMilvus `>=2.4.10,<2.5` | V1 目标；与 Milvus 2.4.x 同代 |
| MinIO 客户端 | MinIO `>=7.2,<8` | V1 目标 |
| 本地嵌入 | FlagEmbedding `1.4.0`、`BAAI/bge-m3` | FlagEmbedding 已固定；模型 commit SHA 待锁定 |
| Transformers | `>=4.44.2,<6` | 兼容范围；最终由 `uv.lock` 固定 |
| Windows GPU Torch | `2.7.1+cu128` | 候选待验证，不是基线 |
| Linux CPU Torch | 与最终 Windows Torch 同版本 | 候选待验证；wheel/hash 未锁定 |
| 传递依赖 | Starlette、AnyIO、Hugging Face Hub client | 由 `uv.lock` 固定；Hub client 只用于联网制品准备 |
| 生成 | DashScope `>=1.26.4,<2`、默认模型标识 `qwen-plus` | V1 目标 |
| PDF 解析 | pypdf `>=6.14,<7` | V1 目标 |
| DOCX 解析 | python-docx `>=1.2,<2` | V1 目标 |
| Markdown 解析 | markdown-it-py `>=4.2,<5` | V1 目标 |
| 编码识别 | charset-normalizer `>=3.4,<4` | V1 目标 |
| 服务发现 | nacos-sdk-python `>=3.2,<4` | V1 目标 |
| 测试 | pytest `>=9.1,<10`、pytest-asyncio `>=1.4,<2`、HTTPX `>=0.28,<0.29`、jsonschema `>=4.26,<5` | V1 目标；jsonschema 校验根级 SSE 契约样例 |
| 依赖工具 | uv `0.11.32` | V1 精确目标 |
| Lint / 类型检查 | Ruff `0.16.0`、mypy `2.3.0` | V1 精确目标 |

`bge-m3` 必须固定实际 40 位 Hugging Face commit SHA；`main`、tag 或只有模型名不算版本。Ollama 不属于 V1 技术基线。

当前开发环境使用的是 HuggingFace cache 的 `master` 快照，尚未锁定 commit SHA；发布前必须替换为固定 commit 的模型目录，并完成逐文件 SHA-256 与 MinIO 不可变版本标识。

## Frontend: Vue 与构建

前端代码位于 `frontend/`，使用 JavaScript/ES Modules，不是 TypeScript 项目。

| 功能 | 技术与版本 | 状态 |
| --- | --- | --- |
| UI 框架 | Vue `3.5.26` | 当前锁定 |
| 构建 | Vite `6.4.1`、`@vitejs/plugin-vue` `5.2.4` | 当前锁定 |
| UI 组件 | Element Plus `2.13.1`、Icons `2.3.2` | 当前锁定 |
| 状态 | Pinia `3.0.4` | 当前锁定 |
| 路由 | Vue Router `4.6.4` | 当前锁定 |
| HTTP/SSE | Axios `1.13.2`；SSE 使用原生 `fetch` | 当前锁定 |
| Vue 工具 | VueUse `14.1.0` | 当前锁定 |
| 图表 | ECharts `5.6.0` | 当前锁定 |
| 富文本 | `@vueup/vue-quill` `1.2.0`、Quill `2.0.2` | 当前锁定 |
| 交互组件 | Vue Cropper `1.1.1`、Vue Draggable `4.1.0` | 当前锁定 |
| 搜索/文件/剪贴板 | Fuse.js `7.1.0`、FileSaver.js `2.0.5`、Clipboard.js `2.0.11` | 当前锁定 |
| 浏览器辅助 | js-cookie `3.0.5`、JSEncrypt `3.3.2`、NProgress `0.2.0` | 当前锁定 |
| 格式与样式 | js-beautify `1.15.4`、Sass Embedded `1.97.2` | 当前锁定 |
| Vite 插件 | auto-import `0.18.6`、setup-extend `1.0.1`、compression `0.5.1`、svg-icons `2.0.1` | 当前锁定 |
| Node.js | 未指定 | 未锁定；须按 Vite 6 兼容范围提交版本文件 |
| Yarn | 存在 `yarn.lock`，CLI 版本未指定 | 未锁定；必须固定包管理器版本 |

V1 不使用 React、TypeScript 或 TailwindCSS。

## Service discovery 与基础设施

| 功能 | 技术与版本 | 状态 |
| --- | --- | --- |
| 注册/配置中心 | Nacos `v3.0.3` | V1 base 编排目标 |
| 流量监控 | Sentinel Dashboard `1.8.9` | 当前锁定 |
| 反向代理 | Nginx，版本未指定 | 未锁定；tag 可移动 |
| Compose 文件格式 | 现有声明 `3.8` | 当前格式；迁移时验证 |
| Docker Engine / Compose CLI | 未指定 | 未锁定；Ubuntu VM 部署前固定 |

现有辅助 Dockerfile 与编排有冲突：MySQL `5.7` 对比目标 `8.0.45`，Nacos `v3.0.2` 对比目标 `v3.0.3`，Redis/Nginx/OpenJDK 使用可移动 tag。迁移到 `deploy/dev/docker-compose-base.yml` 时必须消除；正式镜像使用精确 tag 加 digest。

## Runtime profile 与硬件

| 功能 | 技术与版本 | 状态 |
| --- | --- | --- |
| 开发宿主机 | Windows，具体版本未核实 | 前端、Java、Python 原生运行 |
| 虚拟化 | VMware Workstation `16`、Ubuntu `24` 系列 VM | VM 只运行中间件 |
| GPU | NVIDIA GeForce RTX 4060 Laptop，8,188 MiB | 当前硬件基线 |
| NVIDIA Driver | `591.86` | 当前已核实 |
| CUDA runtime | PyTorch `cu128` wheel 自带 CUDA 12.8 runtime | 候选待验证 |
| GPU profile | FP16、单进程、单 worker、单模型、batch `4`、semaphore `1` | V1 已确认 |
| CPU dev profile | FP32、单 worker、batch `1`、semaphore `1` | V1 已确认；仅排障/可移植性 |

## 版本升级规则

- 直接依赖使用明确范围或精确版本，传递依赖由 lock/依赖清单固定；部署现场禁止重新求解。
- Python 升级必须重建 `uv.lock` 和离线 wheelhouse，并在 Windows CUDA 与 Linux CPU 两端验证。
- 基础镜像和基础设施镜像发布前必须固定精确 tag 与 digest。
- 版本升级必须运行所属模块测试、跨端契约测试和受影响的系统/smoke；resolver 成功不构成兼容证明。
- 候选版本只有具备仓库清单、官方制品和验证证据后才能改为已确认。
