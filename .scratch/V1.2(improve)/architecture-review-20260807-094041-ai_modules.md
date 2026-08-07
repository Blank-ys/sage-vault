# Sage Vault RAG 模块架构深挖候选

- 日期：2026-08-07
- 范围：`ai-modules/services/rag`
- 术语：module、interface、implementation、depth、seam、adapter、leverage、locality

## 候选总览

| 编号 | 候选 | 推荐强度 |
| --- | --- | --- |
| 1 | 收拢 HTTP transport 与 bootstrap 组装 | Strong |
| 2 | 深化 Answering execution module | Worth exploring |
| 3 | 深化 Milvus vector-store adapter 的内部 seam | Strong |
| 4 | 收拢 Indexing publication 与失败补偿 | Worth exploring |

---

## 1. 收拢 HTTP transport 与 bootstrap 组装

**状态：`resolved`**

**推荐强度：Strong**

### Files

- `src/sage_vault_rag/transport/http/app.py`（约 450 行）
- `src/sage_vault_rag/bootstrap/settings.py`
- `tests/contract/test_answer_transport.py`
- `tests/contract/test_indexing_transport.py`

### Before：浅 interface

```text
FastAPI routes
├── 4 套 HMAC / replay 校验
├── SSE event mapping
├── BackgroundTasks runner
└── adapter object graph 组装
    ├── Milvus
    ├── BGE
    ├── DashScope
    └── Nacos
```

### After：深 module

```text
HTTP transport adapter
├── wire / event mapping
└── replay-auth module
        |
        v
application interfaces

Bootstrap assembly
└── validated object graph
        |
        v
ports + adapters
```

### Problem

路由、SSE 映射、4 套鉴权、后台任务和所有 adapter 组装共处一个 module。interface 几乎等于 implementation，修改任一变化轴都穿透同一文件，locality 差。

### Solution

保持现有公开 HTTP seam，把 wire/event mapping、replay-auth 和 bootstrap assembly 收拢为内部 deep module；transport 只依赖 application interface，组装只依赖 ports。

### Benefits

- **locality**：契约变化、鉴权变化、adapter 替换各自集中。
- **leverage**：contract tests 直接击中 transport interface，组装可用 in-memory adapters 验证。
- **测试改进**：可分别验证 wire 映射、replay 规则和对象图组装。
- **删除测试**：删掉任一块不会让复杂度消失，说明当前确有浅 module；深挖后可让测试跨更小 seam。

深入探讨：收拢 HTTP transport 与 bootstrap 组装

### 结论

候选 #1 值得优先实施，但目标不是简单拆分 `app.py`，而是让最高 HTTP seam 变成一个深 module：HTTP caller 只需要了解少量 application interface；认证、wire 映射、异步投递和具体 adapter 组装等复杂性分别收敛在内部 implementation 中。

当前 `src/sage_vault_rag/transport/http/app.py` 同时承担 FastAPI routes、Pydantic 请求模型、SSE 映射、四套 HMAC/replay 校验、`BackgroundTasks` 投递、Nacos 生命周期和 BGE/Milvus/DashScope/Java callback object graph 组装。其 interface 几乎等于 implementation，导致签名规则、契约字段、运行 profile 或 adapter 替换的变化都穿透同一文件，locality 不足。

### 建议的 module 形状

```text
transport/http
├── app.py                 # FastAPI 创建、路由和生命周期挂载
├── schemas.py             # Pydantic transport models
├── events.py              # AnswerEvent -> SSE wire mapping
└── replay_auth.py         # HMAC、timestamp、replay policy

bootstrap
├── settings.py
├── dependencies.py        # RagDependencies / 已组装 object graph
└── factories.py           # concrete adapters -> application services
```

外部 HTTP seam 可保持为 `create_app(settings, dependencies=None, registration=None)`。生产入口由 bootstrap 创建依赖；contract tests 注入 fake application adapters。`transport/http` 不应直接导入 Milvus、BGE、DashScope、MinIO 或 Nacos 的具体实现。`BackgroundTasks` 可以保留，因为 indexing/cleanup 的 wire contract 要求快速返回 `202 Accepted`；但 route 只调用 runner interface，不负责创建 service。

### 必须保持的约束

- 不改变现有 Java-Python HTTP/SSE wire contract、签名字节格式、HTTP 401 行为和 `202 Accepted` 语义。
- `AnswerEvent` 的内部 model 不进入 transport caller；SSE payload 由独立 mapping module 生成。
- Python 仍只拥有 RAG 执行结果，不复制 Java 的 task、QA Record、conversation 或 cancellation 业务状态机。
- bootstrap 只负责依赖组装和资源生命周期，不裁决业务终态。
- `seen_requests` 等 replay 状态不能提升为模块级全局变量；可先保留进程内 store。跨实例 replay 另立 issue 评估 Redis 和一致性。

### 不推荐的拆法

- 把四个签名函数分别拆成四个文件：文件数增加，但 interface 没有变深；应统一为一个 replay-auth module。
- 为每个 route 创建 wrapper class：复杂度只是转移。
- 把 concrete adapter Protocol 暴露给 transport：会扩大调用者必须学习的 interface。
- 使用全局 singleton 保存依赖或 replay state：会破坏测试隔离和多 profile 运行。

### 验证 seam

现有 `tests/contract/test_answer_transport.py` 与 `test_indexing_transport.py` 应继续作为最高可观察 seam，验证 SSE 事件、取消、错误签名、replay、indexing/cleanup 的 `202` 和 runner command，以及 Nacos register/close 顺序。内部 replay-auth、event mapping 和 bootstrap tests 只能辅助定位，不能替代 Java -> RAG 的真实 HTTP/SSE contract。

完成重构后至少运行 `uv run ruff check .`、`uv run mypy .`、`uv run pytest`，并明确报告真实 Milvus、Windows GPU smoke 和部署验证是否执行。

### 推荐实施顺序

1. 提取 `schemas.py`、`events.py`，保持 route 行为不变。
2. 提取统一 `replay_auth.py`，锁定现有签名和 replay 行为。
3. 将 service factory 和 adapter object graph 移到 `bootstrap/factories.py`。
4. 引入可注入的 `RagDependencies`，让 `create_app` 仅消费 application-facing interface。
5. 最后处理资源 close，并补充生命周期 contract test。

成功标准：路由调用者不需要重新了解 Milvus、模型、签名细节或任务组装；契约行为、异步语义和 Java authoritative ownership 保持不变。

---

## 2. 深化 Answering execution module

**状态：`resolved`**

**推荐强度：Worth exploring**

### Files

- `application/answering/service.py`
- `application/answering/cancellation.py`
- `model/events.py`
- `transport/http/app.py`
- `tests/unit/application/test_answering.py`

### Before

```text
answer()
├── embedding
├── retrieval + threshold
├── async generation
├── cancellation polling
├── timing / diagnostics
├── logging / privacy
└── generator close
```

### After

```text
Answer execution interface
├── retrieve evidence
├── refusal policy
├── generation lifecycle
└── cancellation mechanism
        |
        v
AnswerEvent stream
```

### Problem

一个 async generator 同时承担检索、拒答、流式生成、取消、诊断、脱敏和资源关闭；retrieval 异常与 generation 异常的语义还不一致。

### Solution

以“检索-证据判定-生成生命周期”为 deep execution module，保留单一事件 interface；HTTP 仅做事件到 wire 的 adapter，CancellationRegistry 留在内部 seam。

### Benefits

- **locality**：停止、拒答、失败和 generator 关闭的规则集中。
- **leverage**：Java/Python contract 与 unit tests 共用一个事件 interface。
- **测试改进**：增加 retrieval 异常、跨 instance cancellation 和生成器关闭的高 seam 证据。
- **约束**：不能复制 Java 状态机，仍由 Java 拥有最终 QA Record 状态。

---

## 3. 深化 Milvus vector-store adapter 的内部 seam

**推荐强度：Strong**

### Files

- `adapters/milvus/store.py`
- `ports/vector_store.py`
- `tests/unit/adapters/test_milvus_store.py`
- `tests/integration/milvus/test_milvus_writer.py`

### Before

```text
VectorStorePort
        |
        v
Milvus adapter
├── connection / cache
├── schema / drop-recreate
├── index
├── entity codec
├── query escaping
└── result mapping
```

### After

```text
VectorStorePort
        |
        v
Milvus adapter
├── collection lifecycle
├── record codec
└── query builder
        |
        v
Milvus SDK
```

### Problem

一个 adapter 同时处理连接缓存、schema 校验与 drop/recreate、索引、实体编码、表达式转义和结果映射；变化轴多，locality 不足。

### Solution

保持单一 `VectorStorePort`，在 adapter 内部形成 collection lifecycle、record codec、query builder 三个 internal seam；不改变 ADR-0001 的单 Collection + `knowledgeBaseId` 过滤决策。

### Benefits

- **leverage**：application 不学习 Milvus 细节，替换或升级 SDK 的影响集中。
- **locality**：schema 与编码故障在一个 adapter 内定位。
- **测试改进**：增加 schema migration、并发初始化和连接恢复的 seam tests。
- **ADR 约束**：不改变单 Collection 与 `knowledgeBaseId` 过滤。

---

## 4. 收拢 Indexing publication 与失败补偿

**推荐强度：Worth exploring**

### Files

- `application/indexing/service.py`
- `model/indexing_result.py`
- `ports/callback.py`
- `tests/unit/application/test_indexing.py`
- `tests/integration/test_*_indexing_end_to_end.py`

### Before

```text
index()
├── 先删旧向量
├── 下载 / 解析 / 切块 / 嵌入 / 写入
└── 任意异常
    ├── 再次清理
    └── 失败回调
        └── 可能触发清理语义混淆
```

### After

```text
publication module
├── prepare vectors
├── publish
├── success result
└── failure seam -> compensation
                         |
                         v
                    callback adapter
```

### Problem

发布、补偿清理和回调都在一个浅 interface；回调失败也可能触发清理，且 `_cleanup` 的 `chunks` 参数未使用，原子发布语义不够清晰。

### Solution

将“准备/发布/补偿”内聚为 publication module，明确成功写入与失败清理的 seam；callback 只报告结果，不参与补偿决策。保持 Java authoritative task 状态，不在 Python 复制状态机。

### Benefits

- **locality**：重试、部分写入和回调失败的规则集中。
- **leverage**：一次定义发布语义，多个文档格式与 adapter 复用。
- **测试改进**：补充 callback-after-success failure、同文档并发 attempt、部分写入测试。
- **约束**：Python 不复制 Java 拥有的业务状态机。

---

## ADR 核对

- **ADR-0001：Java 负责业务中台，Python 负责 RAG 链路**
  - 四个候选都保留 Python 的 RAG owner。
  - 不把 Java 的 QA Record、任务或取消状态机复制到 Python。
  - 不改变 Java/Python 公开 seam。

- **ADR-0002：百炼生成与数据出网**
  - Answering 候选保留 GenerationPort 与 DashScope adapter。
  - SDK 类型和凭据仍停留在 adapter 内部。
  - 不把真实百炼调用引入自动化测试。

## Top recommendation

优先选择 **候选 1：收拢 HTTP transport 与 bootstrap 组装**。

原因：它位于最高公开 seam，能同时改善契约测试、鉴权变更和 adapter 替换的 locality；改动可保持现有 application interface 与 ADR，不需要重新设计业务状态。

请回复候选编号（1-4），再进入 grilling loop，讨论约束、deep module 形状、seam 和可保留测试。
