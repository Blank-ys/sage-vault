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
- 文档可见范围变更时,需给其 chunks 重打标签(重写元数据)。规章制度更新慢,可接受。
