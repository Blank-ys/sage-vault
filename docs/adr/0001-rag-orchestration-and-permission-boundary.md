# RAG 编排归 Python,权限授权归 Java,检索按标签求交过滤

## Status

accepted

## 决策

RAG 全链路(检索 → 重排 → 生成)的编排大脑放在 **Python AI 服务**,一次问答一趟 RPC 返回答案。**Java 业务中台**是权限与数据的权威源:它不执行检索,但决定 Python 能在哪些内容里检索。权限过滤采用**角色/部门标签求交**——入库时每个 chunk 携带其文档的可见范围标签,查询时 Java 下发用户的授权声明,Python 按 `chunk.visible_tags ∩ user.tags ≠ ∅` 过滤;另设一个**短小的例外覆盖层**(显式 grant/deny 的 documentId 列表)处理临时单独授权。

## Considered Options

- **Java 当 RAG 大脑,Python 退化为原子算法接口**:业务/权限全在 Java,但一次问答需多趟往返,且 RAG 调参每次都要动 Java。因 RAG 是高频迭代的算法密集体,内聚在 Python 才符合选异构架构的初衷,故否决。
- **documentId 全量白名单下发**:按 ID 授权,规模 = 文档数(无上界),普通员工可见上千篇制度时 payload 与向量库 `IN` 过滤双双退化。改为按角色授权(规模 = 用户角色数,有上界),故否决。

## Consequences

- 权限的**授权在 Java、执行在 Python**,两者之间需要一份清晰契约(授权声明的结构、Python 忠实过滤的义务)。这是一条安全边界,必须有测试守护:用户无权的文档内容,即使只是喂给 LLM 也不得出现。
- 文档可见范围变更时,需给其 chunks 重打标签(重写元数据)。**变更为异步流程**(见下方"可见范围变更改为异步"):部分制度文档 chunk 数很大,同步重打标签会让管理员长时间干等,故改为 Java 同步下发**哨兵标签**(fail-closed,详见 `CONTEXT.md`)+ 异步触发 Python 重打标签 + 回调更新状态。

## 可见范围变更改为异步(2026-07-22 补充裁定)

最初决策是可见范围变更**同步生效**,不引入异步窗口期。经重新评估,推翻该裁定,原因:①与入库流程统一为同一套"Java 触发 Python 异步任务 + 回调"机制,降低工程复杂度;②部分制度文档 chunk 数很大,同步重打标签会阻塞管理员操作。

为避免异步窗口期内旧标签继续生效造成权限滞后泄露,采用 **fail-closed** 方案:

- 管理员提交可见范围变更后,Java **同步**执行一次轻量的 Milvus 标量更新,把该文档的标签替换为**哨兵标签**(不与任何用户标签相交的空集),使文档对所有人(含原本有权限的人)在窗口期内暂时不可检索,随后立即返回成功。
- Java 异步触发 Python 完成真正的重打标签(按新可见范围写入正确标签)。
- Python 处理完成后回调 Java,Java 将文档的 `visibilityStatus` 更新为 `SYNCED`(成功)或 `FAILED`(失败,附原因)。`FAILED` 时**继续保持哨兵标签**,不回退到旧标签或新标签,避免用一个未确认生效的标签状态提前放行。
- `visibilityStatus`(`SYNCED`/`UPDATING`/`FAILED`)与入库状态字段 `ingestStatus`(原名 `status`,为避免歧义已重命名,见 spec)是两条独立状态机,不复用同一个字段,因为两者在同一状态值下语义不同。
- 竞态规避:仅当 `ingestStatus=READY` 且 `visibilityStatus=SYNCED` 时才允许发起新的可见范围变更;`ingestStatus` 未到终态(`READY`/`FAILED`)前禁止修改可见范围。
- Java↔Python 的触发与回调:Java 发起非阻塞 HTTP 调用触发 Python(入库 `/ingest`、可见范围变更 `/reindex-visibility`);Python 完成后回调 Java 的独立回调接口(`/callback/ingest`、`/callback/visibility`)。双方均通过 **Nacos 注册与服务发现**定位对方实例(不写死 host:port),但服务发现只解决寻址,不做身份校验——回调接口仍需**共享密钥鉴权**,防止内网任意服务伪造回调篡改文档状态。回调不经过网关(网关的 `AuthFilter` 面向用户 JWT 设计,与这类系统间调用不匹配)。
- 此决策不改变 ADR-0001 的核心安全边界:调用 LLM/执行检索前的标签求交逻辑不变,哨兵标签只是让"求交"在窗口期内对任何人都返回空集。
