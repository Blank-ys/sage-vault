# 确定 Java-Python 契约所有权与运行时 seam

Type: grilling
Status: resolved
Blocked by: 01, 02

## Question

在 Java 作为业务权威、Python 负责 RAG 链路的既定职责下，异步入库与清理、回调、流式问答、取消、幂等、错误和可观测性契约应由哪一侧拥有并存放，如何版本化和验证？请确定 Nacos 发现、HTTP/SSE 适配器、契约模型、生成或手写策略、测试替身及禁止跨越的依赖方向，使双方能独立实现而不复制业务状态机。

## Answer

### 契约所有权与存放

- 根目录 `contracts/java-python-rag/v1/` 是 Java-Python HTTP、回调和 SSE wire contract 的唯一权威来源。实际机器可读契约不得放入 `.agents/rules/`；`.agents/rules/backend.md` 与 `.agents/rules/ai-modules.md` 只记录修改权限、兼容规则、必跑验证并链接该目录，根 `AGENTS.md` 继续只做规则地图。
- 契约由 Java 与 Python 共同维护，但语义所有权分开：Java 定义业务身份、任务与尝试、允许的业务状态转换、重试裁决和取消语义；Python 定义 RAG 执行结果、流事件能力与安全诊断元数据。任何一方不得单独把本端实现细节提升为跨进程事实。
- `openapi.yaml` 描述命令受理、结果回调、流式问答、取消和健康 interface；具名 SSE 事件使用独立 JSON Schema；`errors.yaml` 注册数值错误码、含义、可重试性与建议 HTTP 映射。契约目录同时保存最小成功、失败和边界样例。
- Java transport DTO 与 Python Pydantic transport 模型在各自 adapter 内手写，不从 schema 生成代码。application interface 只使用本端项目类型；Spring、Feign、FastAPI、Pydantic、Milvus、MinIO 或供应商 SDK 类型不得越过 adapter seam。

### 异步命令、回调与企业文档传递

- 文档入库和清理采用“至少一次投递 + 幂等收敛”。Java 为任务保存稳定 `taskId`，为知识管理员发起的每次新尝试递增 `attempt`；网络超时只表示结果未知，自动重投沿用同一 `taskId`/`attempt`。
- Python 对命令快速返回 HTTP `202 Accepted`，并保证重复的同一尝试不会重复发布副作用。完成后回调 Java，携带 `taskId`、`attempt`、结果、数值错误码或安全诊断摘要以及 `requestId`。
- Java 的当前任务状态是唯一裁决者：重复回调可安全重放，旧 `attempt` 被忽略并记录，未知任务被拒绝，只有当前尝试能推动业务状态转换。Python 不复制 Java 的任务状态机。
- Java 将企业文档写入 MinIO 后，通过限时预签名下载 URL 向 Python 提供不可变 `documentId`、对象版本或校验和。Python 不持有 MinIO 管理凭据，也不上传或删除原文件；URL 过期或校验失败使本次尝试失败，由 Java 依任务规则重投或重试。
- 清理命令携带目标资源身份和版本，防止旧任务删除后来重新上传的企业文档。Java 删除 MinIO 原文件，Python 清理其解析临时产物和 Milvus 向量，Java 汇总最终业务状态。

### 流式问答与取消

- Java 为每条活跃问答创建 `generationId`，通过 Nacos 选择一个 RAG 实例并在该问答生命周期内保持实例亲和。Nacos 只负责发现逻辑服务名；实例选择、超时、有限重试和亲和全部封装在 Java adapter 中，application interface 不感知 Nacos、HTTP 或 SSE。
- Python 的回答 HTTP interface 输出项目自有 SSE 事件；Java逐事件校验、转发并增量保存已生成文本。Python不另行回调问答终态，流的终止事件表示执行结果，Java独自决定问答记录是完整、拒答、已停止或未完成。
- 显式取消使用同一 `generationId` 调用原 RAG 实例，Python尽力中止生成并返回已接受、已经结束或未知结果。取消不得被路由到另一实例；V1 不建立跨实例共享的活跃生成注册表。
- 原实例不可达时，Java立即关闭浏览器流，将已有文本保存为“已停止”，并记录 Python 取消未确认；意外断流保存为“未完成”，不得与用户停止混同。未来横向扩容若无法保证实例亲和，再单独引入共享取消协调机制。

### 内部认证与可观测上下文

- RAG 服务只注册到 Nacos 内部网络，不经浏览器网关公开，Java是唯一业务调用方。Nacos发现和内网可达性不构成认证。
- Java到Python及Python回调Java都使用部署配置注入的共享密钥进行请求签名，并携带时间戳与短重放窗口；具体规范固定在 wire contract。不得转发用户 Bearer token、用户角色或凭据，Python不理解 RuoYi 授权模型。
- 所有跨端调用传播 `requestId`；异步链路另传 `taskId`/`attempt`，问答链路另传 `generationId`。日志只记录这些关联标识和契约允许的诊断元数据，不记录问题、回答、企业文档片段、完整提示词、原始供应商响应或密钥。

### HTTP 与数值错误码

- HTTP 状态表达请求层结果，例如 `202` 已受理、`400` 契约不合法、`401` 内部签名失败、`409` 状态冲突、`429` 限流以及 `500`/`503`/`504` 服务故障。异步最终失败由回调表达，流式执行失败由 SSE `error` 事件表达。
- 业务错误码使用六位整数 `8CCDDD`：`8` 表示 Sage Vault Java-Python RAG 契约，`CC` 是类别，`DDD` 是类别内序号。类别固定为 `01` 契约校验、`02` 内部认证、`03` 任务/尝试、`04` 企业文档源、`05` RAG依赖、`06` 生成/取消、`99` 未分类内部故障。
- wire 上只能传注册过的整数错误码，不得传 `INVALID_REQUEST` 一类字符串码。代码内部可以使用有语义的常量名，但其数值、含义、可重试性和 HTTP 映射以 `errors.yaml` 为准，任何一端不得临时发明编号。
- 错误载荷只包含数值 `code`、安全摘要、`retryable`、关联标识和允许的阶段元数据。Java独自把错误映射为重试策略、业务状态和用户文案；异常类名、堆栈和供应商原始响应不得进入契约。

### 版本兼容与验证 seam

- `v1` 内只允许新增可选字段、增加明确声明可忽略的 SSE 事件和注册新错误码。删除或改名字段、改变既有含义、收紧校验、改变状态语义均属于破坏性变更，必须进入新的版本目录。
- 接收方忽略未知可选字段和声明为可忽略的未知事件；对未知必需事件、未知错误码、非法状态组合或签名失败必须拒绝并记录，不能猜测处理。
- Java与Python CI都校验 schema、共同样例及本端序列化模型。Java consumer tests 验证 Python provider，Python consumer tests 验证 Java callback provider；假 Python provider、假 Java callback receiver 和受控流 provider 必须实现同一 wire contract，不得提供绕过契约的测试专用 interface。
- 最高验证 seam 仍是浏览器到 Java 的系统验收；跨端契约测试补充验证异步受理、重复与乱序回调、预签名 URL 失效、SSE 事件顺序、显式取消、实例不可达、数值错误码、签名与关联标识传播。
