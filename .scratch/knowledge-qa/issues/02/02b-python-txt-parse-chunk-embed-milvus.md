# 02b — Python RAG TXT 入库：解析、切块、嵌入、Milvus 写入

**What to build:** 在 Python RAG 服务内新增入库流水线，异步读取 TXT，按自然段和可配置长度/重叠切块，使用本地 `bge-m3` 嵌入，并将带 `knowledgeBaseId`、文档 ID、片段 ID、文件名和顺序的向量写入单个 Milvus Collection。

**Blocked by:** 01 — 打通空知识库问答细线；02a 完成后需与 02c 对齐契约.

**Status:** resolved

- [x] 新增 `application/indexing` 深模块；transport 只做协议转换
- [x] 实现 TXT parser adapter
- [x] 按自然段和可配置长度/重叠参数切块
- [x] 实现本地 `bge-m3` 嵌入 adapter，支持 GPU/CPU profile
- [x] 定义 Milvus collection schema，写入带 `knowledgeBaseId`、`documentId`、`chunkId`、`filename`、`sequence`、`text` 的向量
- [x] 入库失败时清理本次尝试写入的 Milvus 向量和解析产物
- [x] Python 应用测试、真实 Milvus 集成测试通过（Milvus 环境不可达时自动跳过）

## Answer

已在 `ai-modules/services/rag` 实现 Python RAG TXT 入库流水线：

- `application/indexing/service.py`：编排下载 → 解析 → 切块 → 嵌入 → Milvus 写入 → 回调，失败时清理本次写入的向量。
- `adapters/txt_parser/parser.py`：基于 `charset-normalizer` 自动探测编码的 TXT 解析器。
- `adapters/chunker/chunker.py`：优先保持自然段落边界，超长段落按可配置长度/重叠切分。
- `adapters/bge_m3/embedder.py`：本地 `bge-m3` 嵌入，支持 `gpu`（FP16/CUDA）与 `cpu-dev`（FP32/CPU）profile；模型加载与推理在 executor 中执行，并带执行槽 semaphore 与队列溢出保护。
- `adapters/milvus/store.py`：Milvus collection schema 与写入/清理适配器，字段包含 `knowledgeBaseId`、`documentId`、`chunkId`、`filename`、`sequence`、`text` 和 1024 维向量；删除表达式已做转义防注入。查询前自动 `collection.load()`，新建 collection 时创建默认 `FLAT` + `L2` 索引以满足 Milvus 2.4 加载要求；距离类型与向量索引将在 02d 检索评测后替换为最终选型。
- `adapters/document_storage/http_client.py`：通过 Java 下发的限时 URL 下载原文件。
- `ports/`：定义了 `DocumentStoragePort`、`TextParserPort`、`ChunkerPort`、`EmbeddingPort`、`VectorStorePort`、`CallbackPort`。
- `transport/http/app.py` 暂未暴露入库 endpoint；`IndexingService` 组装与 HTTP 协议转换由 02c 异步入库契约确定后接入。
- `pyproject.toml`：已引入 `langchain>=1.3.14,<2` 作为后续 RAG 编排依赖；Milvus 写入/清理仍直接使用 PyMilvus，以保留 02b 要求的独立标量字段 schema。
- `tests/`：新增单元测试与集成测试；在本地 Milvus `192.168.150.100:19530` 与本地 `bge-m3` 模型路径可用时，全量测试可全部通过。

验证：
- `uv run ruff check .` 通过
- `uv run mypy .` 通过
- `uv run pytest -v` 通过（20 passed，含真实 Milvus 集成测试）
- 真实 Milvus 集成测试启用命令：
  ```powershell
  $env:SAGE_VAULT_RAG_RUN_MILVUS_TESTS="true"
  $env:SAGE_VAULT_RAG_MILVUS_HOST="192.168.150.100"
  $env:SAGE_VAULT_RAG_MILVUS_PORT="19530"
  $env:SAGE_VAULT_RAG_EMBEDDING_MODEL_PATH="F:\tmp\models\BAAI--bge-m3\snapshots\master"
  uv run pytest -v
  ```

注意：
- 当前 `uv.lock` 中 torch 解析为 2.12.1+cpu，与技术栈文档中候选的 2.7.1+cu128 GPU 基线不一致；待目标环境验证后按 `docs/technology-stack.md` 流程锁定。
- 本地 `bge-m3` 模型当前使用 `master` 快照，未锁定 40 位 commit SHA、未做 SHA-256 校验、未接入 MinIO 不可变版本标识；开发环境暂不强制，发布前必须按 `docs/technology-stack.md` 和 `.agents/rules/ai-modules.md` 补齐。
