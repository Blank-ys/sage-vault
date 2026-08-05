# 01 — 打通空知识库问答细线

**What to build:** 知识管理员能够创建一个知识库，普通用户能够看到它、绑定新会话并发起问题；浏览器经 Java 到 Python 的 SSE 链路返回“该知识库暂无可用文档”，从而建立第一条可运行、可验收的端到端问答细线。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 知识管理员可创建、查看和修改知识库；名称按不区分大小写全局唯一，所有已登录用户只能选择状态为可用的知识库。
- [x] 普通用户可绑定一个知识库新建会话；空知识库允许创建会话，但提问得到明确的“该知识库暂无可用文档”拒答。
- [x] 浏览器只连接 Java，Java 通过 Nacos 发现 Python，并以 SSE 转发至少 `started` 和 `refused` 事件；匿名请求被拒绝。
- [x] 建立项目首个外部 HTTP/SSE 系统验收接缝，使用真实 Java、Python 和 MySQL，生成侧注入确定性假模型。
- [x] 普通用户看不到知识库管理入口、企业文档列表、预览或下载能力。

## Comments

### 2026-07-25 MyBatis refactor decision

The complete ready-for-agent design and testing specification is [Knowledge Base Management MyBatis Refactor](../../kb-mybatis-refactor/spec.md). This issue remains the product acceptance owner; the linked specification defines the Java module shape used to finish it.

Before completing Issue 01, refactor only the capabilities already introduced by this walking skeleton: knowledge base, conversation, and QA record. Organize each capability by `controller`, `service`/`service.impl`, `domain`, and `mapper`; use `XxxService` as the public application interface and `XxxServiceImpl` as its implementation. Module-owned MySQL persistence calls MyBatis Mapper interfaces directly, with SQL in capability-specific XML files and independent `XxxEntity` persistence models. External integrations such as RAG and audit retain port/adapter boundaries.

This work remains part of Issue 01 because its implementation is uncommitted and final system acceptance and review have not run. Do not pre-create document, feedback, or other future capability shells. Use the MyBatis version managed by `backend/pom.xml`; do not introduce MyBatis-Plus or duplicate managed versions. All prior Java verification evidence must be rerun after this refactor.

Keep the existing minimal whitelist-only `ManagementAudit` call and adapter, but do not add or claim transaction ordering, reliable delivery, retry, compensation, outbox, or audit-specific transaction infrastructure in Issue 01. Those semantics belong to the later roles, audit, and safe-logging issue.

For the Issue 01 QA record, `STARTED` means Java accepted the generation. The Python `started` SSE event is forwarded to the browser but does not cause another database update. Do not add `PENDING` or `ACCEPTED`; the only final transitions in this tracer are `STARTED -> REFUSED` and `STARTED -> UNFINISHED`, with duplicate final events idempotent and late events unable to replace an existing final state.

Knowledge-base name uniqueness uses a Service pre-check for clear normal-path feedback plus the existing MySQL `normalized_name` unique constraint as the final concurrent arbiter. Keep normalization in `KnowledgeBaseName`, translate duplicate-key failures in `KnowledgeBaseServiceImpl`, and verify case-insensitive conflict plus the database-constraint fallback with the production SQL on real MySQL.

For Issue 01 transaction scope, do not add transactions mechanically to every write. Knowledge-base create/update and conversation create remain single-SQL operations without an explicit transaction; keep the minimal audit call without adding ordering guarantees. `QaRecordServiceImpl` owns short transactions for record creation and conditional final-state decisions. `ConversationServiceImpl.askQuestion` must never hold a transaction across the RAG stream. Add owner-Service transactions later only when a use case introduces multiple writes that must commit atomically.

The non-paginated available-knowledge-base list is filtered in MySQL using the status selected by `KnowledgeBaseServiceImpl`. Both the management list and the available list are ordered by `updated_at DESC, id DESC`, using separate Mapper queries. Add `created_at` and `updated_at` to `sv_knowledge_base`; do not expose them in the Issue 01 response. The repository evidence currently says the schema has not been executed. Recheck the target database before applying it: if the table already exists, stop and add a new immutable incremental script instead of rewriting an applied schema.

Remove the public `changeStatus(name, status)` test helper. Issue 01 creates knowledge bases as `AVAILABLE` and exposes `requireAvailable(id)` as the narrow cross-capability check; it does not expose a generic status setter. Future deletion states must be changed through explicit business commands owned by the corresponding deletion issue. Tests arrange unavailable state through a fixture or fake Mapper and must not widen the public Service interface.

### 2026-07-25 implementation handoff

Paused at the user's request before final verification, code review, issue resolution, or commit. The working tree intentionally contains uncommitted implementation changes; unrelated untracked `.claude/` must remain untouched.

Completed implementation:

- Added the standalone `backend/ruoyi-kb-management` release unit with knowledge-base, conversation, and answering application interfaces; JDBC production adapters; Spring HTTP/SSE controllers; Nacos-discovered RAG adapter; business exceptions; schema and knowledge-administrator seed SQL.
- Added `ai-modules/services/rag` with a locked Python 3.12 FastAPI service, empty-knowledge-base answering application, signed internal SSE transport, replay-window protection, and Nacos registration through the actual `v2.nacos` SDK API.
- Added root `contracts/java-python-rag/v1` OpenAPI, `started`/`refused` schemas, errors, example, and contract checks.
- Added frontend `knowledge-bases` and `conversations` features. The QA workspace is an authenticated constant route; the management page is supplied only by the permission-backed database menu. Raw SSE parsing stays in the conversations API adapter.
- Added `system-tests/knowledge-qa/test_empty_knowledge_base.py`, a black-box Gateway-only acceptance harness requiring real Java, Python, Nacos, Redis, and MySQL.

Key decisions:

- Issue 01 is the walking skeleton that creates the minimum real module structure; no empty framework or future document/feedback capabilities are pre-created.
- Java owns knowledge-base/conversation business state. Python owns the empty-library refusal execution. Java calls Python only through `RagAnswerPort` and Nacos discovery.
- Internal HMAC canonical data is `knowledgeBaseId:requestId:generationId:timestamp:sha256(question)`. Python rejects expired timestamps and repeated request/generation pairs inside the replay window.
- General-user QA is a constant authenticated route. Knowledge-base management visibility and API authorization use `sage:knowledge-base:manage`; no enterprise-document UI exists in this issue.
- Business failures remain HTTP 200 `R.fail(code, message)`. The frontend SSE adapter checks content type so a pre-stream `R.fail` is surfaced rather than treated as an empty stream.

Verification already completed before the latest unverified edits:

- Java application and real local HTTP/SSE consumer tests: 7 tests passed.
- Python Ruff and strict mypy passed before Nacos/signature follow-up edits; pytest had 2 tests passing, later replay/schema changes are not yet verified.
- Root contract example unittest passed before formal `jsonschema` validation was added.
- Frontend production Vite build passed via `node node_modules/vite/bin/vite.js build`. `yarn --cwd frontend build:prod` could not locate `vite.cmd` despite the package existing; investigate the Windows Yarn shim rather than claiming that exact command passed.
- Maven production package for the new Java module passed before the latest edits.

Required continuation steps:

1. Run `uv lock` after the new `jsonschema` dependency, then `uv run ruff check .`, `uv run mypy .`, and `uv run pytest`; fix any path/assertion/import issues.
2. Run `python -m unittest discover -s contracts/tests -v` inside the RAG uv environment because root contract tests now import `jsonschema`.
3. Re-run Java module tests and package. Add HTTP authorization tests for anonymous/general-user/admin behavior; current application tests do not prove the controller/AOP permission paths.
4. Add or document the Gateway route/Nacos configuration needed for `/ruoyi-kb-management/**` without committing an unapproved real Nacos config. Verify the service route is reproducible.
5. Run the frontend production build again after the SSE error-handling change. Browser verification has not been performed.
6. If a real environment is available, initialize MySQL with `001_schema.sql` and `002_seed.sql`, start Gateway/Java/Python/Nacos/Redis, and run the black-box system test. This machine had no Docker executable and no listeners on ports 3306/8848, so the required real system acceptance has not run and the checklist must not be marked complete yet.
7. Run the full backend Maven suite once, then use the `code-review` skill for parallel Standards and Spec review, repair findings, update this issue to resolved only if all acceptance requirements are genuinely met, and commit the work.

Known residual risks:

- The system-test asset exists but real Java+Python+MySQL/Gateway/Nacos acceptance remains unproven.
- Java/Python contract tests need final confirmation that both consumers read the root contract rather than duplicating literals; the Java HTTP provider fixture still contains literal SSE payloads.
- Python Dockerfile uses `python:3.12.7-slim` without a digest. It is a development asset, not a verified reproducible release baseline.
- Local `uv` was `0.11.29`, while `docs/technology-stack.md` records target `0.11.32`; the generated lock is development evidence only until verified with the target CLI.

### 2026-07-27 final verification, review, and resolution

All seven continuation steps completed on a real local environment (Gateway 8899 / Auth / System / kb-management 9216 / sage-vault-rag 8000, all five services registered in Nacos at 192.168.150.100:8848; MySQL and Redis on 192.168.150.100).

Verification evidence:

1. Python: `uv lock` regenerated (uv 0.11.29, target 0.11.32 deviation stands), `ruff check` clean, strict `mypy` clean over 16 files, `pytest` 3 passed.
2. Root contract tests: `python -m unittest discover -s contracts/tests -v` inside the RAG uv environment, 1 test OK with formal `jsonschema` Draft 2020-12 validation of the root schemas.
3. Java: module tests 27/27 passed including 5 real-MySQL integration tests (enabled via `SAGE_VAULT_MYSQL_TEST_*` env vars); `package` succeeded. HTTP authorization tests exist and pass: `KnowledgeBaseAuthorizationTest` (4) and `ConversationAuthorizationTest` (2) cover anonymous/general-user/admin paths.
4. Gateway route for `/ruoyi-kb-management/**` documented reproducibly in `system-tests/knowledge-qa/README.md`; no unapproved real Nacos config committed. Route verified live through the running Gateway.
5. Frontend `yarn build:prod` passed (must be run from an uppercase drive letter `F:\` on Windows; lowercase breaks vite:html-inline-proxy).
6. Black-box system test `system-tests/knowledge-qa/test_empty_knowledge_base.py` passed against the real stack. One test assertion was corrected during this run: RuoYi Gateway rejects anonymous requests with HTTP 200 plus body code 401 (the repo-wide `R.error` convention), so the anonymous assertion now checks HTTP 200 and body code 401 instead of HTTP 401/403.
7. Full backend Maven suite: BUILD SUCCESS across all 23 modules. `code-review` skill ran Standards and Spec reviews in parallel from baseline `0e7fe621`; Spec review confirmed the state machine (`STARTED -> REFUSED/UNFINISHED` conditional updates), removal of `changeStatus`, ordering, and that short transactions never span the RAG stream (terminal-state writes are cross-bean calls from reactive callbacks, each holding only a single-UPDATE transaction).

Acceptance item 5 evidence: the management page is supplied only by the permission-backed database menu (no static route), and the passing authorization tests plus the live system test prove general users are denied `sage:knowledge-base:manage` APIs.

Review findings accepted as known debt (user decision, not fixed in this issue):

- P0 debt: `backend/ruoyi-kb-management/src/main/resources/application.yml` contains a plaintext datasource password and `sage-vault.rag.signing-key`, violating the "secrets only in `.env`" rule. To be fixed when configuration migrates to Nacos/env placeholders; do not copy this pattern into new modules.
- P1 debt: the `-Dfile.encoding=UTF-8` `jvmArguments` block in `backend/ruoyi-kb-management/pom.xml` stays commented out by user choice; running the jar on Windows still requires passing `-Dfile.encoding=UTF-8` manually.
- `HealthController.java` (`/checkAliveServer`) is out-of-spec, user-added for health probing; it stays untracked in the working tree and is excluded from this issue's commit.

Final commit is performed by the user.
