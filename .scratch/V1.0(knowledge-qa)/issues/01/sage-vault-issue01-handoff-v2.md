# Sage Vault Issue 01 Handoff

## Focus

Continue the implementation of `.scratch/knowledge-qa/issues/01-empty-knowledge-base-qa-tracer.md` and the handoff file at `.scratch/knowledge-qa/issues/sage-vault-issue01-handoff.md`. The next session should finish the real integration acceptance, resolve remaining review findings, update the issue evidence/status accurately, and commit only after the required review.

## Current repository state

- Branch is `master`, 14 commits ahead of `origin/master`; implementation changes are intentionally uncommitted.
- Preserve unrelated untracked `.claude/`.
- User explicitly resolved the package-root documentation conflict: the Java root package is `com.sagevault.kb`. `docs/code-framework.md` was updated, including its target tree paths.
- Working-tree changes include the Java KB release unit, Python RAG service, root contracts, frontend features, system-test harness, docs, and verification fixes. Inspect `git status`, `git diff HEAD`, and untracked files before editing.

## Completed verification

- Python RAG: `uv lock`, Ruff, strict mypy, and pytest pass. Tests currently cover the empty-library SSE stream, invalid signature, replay rejection, and use an explicit no-op registration adapter in tests.
- Root contract unittest passed before the latest Java-only changes; rerun it with the RAG uv environment.
- Java KB module: affected tests pass, including HTTP authorization, application behavior, RAG discovery/signature, audit whitelist, and deletion-window refusal. Latest module test count was 11 passing. Package succeeded with `mvn -f backend/pom.xml -pl ruoyi-kb-management -am -DskipTests package`.
- Full backend Maven suite passed once before the latest audit/persistence changes; rerun after final repairs.
- Frontend direct build passed with `node node_modules/vite/bin/vite.js build`. Exact `yarn --cwd frontend build:prod` still fails because the Windows Yarn shim cannot find `vite`; do not claim the exact command passed.
- Real endpoint TCP checks from the approved external shell: MySQL, Redis, and Nacos at the user-provided host were reachable. Nacos `/nacos/v1/ns/operator/metrics` returned `{"status":"UP"}`; old health URLs returned 404/410.
- Read-only MySQL query confirmed version `8.0.45`, database `ry-cloud`, and no existing `sv_knowledge_base`, `sv_conversation`, or `sv_qa_record` tables.
- Packaged Java bootstrap reached the real application and failed only because Nacos lacked the required `ruoyi-kb-management-dev.yml` Data ID; this confirms the prior MVC/WebFlux startup concern was fixed by changing dependencies to `spring-boot-starter-webmvc` plus `spring-boot-starter-webclient`.
- No real Gateway/Nacos/MySQL end-to-end acceptance has run. No browser verification has run.

## Latest implementation changes after prior handoff

- Java dependency switched from `spring-boot-starter-webflux` to `spring-boot-starter-webmvc` plus `spring-boot-starter-webclient`.
- Java now has mandatory RAG signing key and Nacos-discovered registration assumptions.
- Python production settings require `SAGE_VAULT_RAG_NACOS_SERVER_ADDRESS`; `create_app` accepts an explicit registration port so tests use a no-op registration without weakening production bootstrap.
- Removed the unpinned Python Dockerfile instead of registering a movable image as a release baseline.
- Added `KnowledgeBaseName` value type so persistence no longer imports `KnowledgeBaseService`; removed repository `nextId()` methods and let `save` allocate IDs.
- Added a `sv_qa_record` schema and minimal Java record repository for `STARTED`, `REFUSED`, and `UNFINISHED`. `AnswerService` rechecks knowledge-base availability before asking and records an unfinished result when the RAG stream errors, including subscription-time errors.
- Added `ManagementAudit` application port and `RuoyiManagementAudit` adapter using existing RuoYi `RemoteLogService`, with a whitelist-only event. Added `ruoyi-common-log` dependency and `@EnableRyFeignClients`.
- Added HTTP black-box assertions for general-user management denial and case-insensitive duplicate names.
- Added an audit whitelist unit test.

## Mandatory external approval still needed

The user supplied reachable MySQL, Redis, and Nacos endpoints, but did not yet authorize writes. Do not execute these until the user explicitly approves:

1. Execute repository scripts `backend/ruoyi-kb-management/sql/001_schema.sql` and `002_seed.sql` against the intended test database. This is a database mutation; check again that the three `sv_*` tables are absent/present before running and never overwrite an existing schema.
2. Add `ruoyi-kb-management-dev.yml` to Nacos `DEFAULT_GROUP` with the non-secret Java datasource/RAG service configuration required by the module.
3. Merge the following route into the existing environment-owned `ruoyi-gateway-dev.yml` route list, preserving all other routes:

```yaml
- id: ruoyi-kb-management
  uri: lb://ruoyi-kb-management
  predicates:
    - Path=/ruoyi-kb-management/**
  filters:
    - StripPrefix=1
```

Do not commit credentials, passwords, Nacos auth secrets, or the HMAC signing key. The HMAC key is only a shared internal Java/Python request-signing secret and must be injected into both processes through environment/secret management.

## User credentials needed

The database contains enabled RuoYi users named `admin` and `ry`, but their passwords/tokens were not provided. Ask for two short-lived access tokens (preferred) or test-only credentials: one knowledge administrator and one general user. Never log or save them.

## Review findings to resolve or explicitly narrow

The two-axis review found:

- Standards: audit, Java-owned QA persistence, capability package layout, dependency/image registration, Nacos mandatory registration, and speculative `nextId` details. Several were addressed above; rerun review after the latest diff.
- Spec: Java startup dependency (addressed), asking after KB becomes unavailable (addressed), deterministic generation fake requirement (likely out of scope for empty-library short-circuit; document the narrow behavior in the issue/spec mapping rather than adding speculative infrastructure), black-box role/uniqueness checks (addressed), and conversation first-question title (belongs to a later conversation issue; explicitly record that Issue 01 does not claim it).

Use the code-review skill again with fixed point `HEAD` (or the pre-work commit `0e7fe6213d8d1152a02206cd3b27c4626c91866a` if comparing the whole implementation) and repair any new hard findings before commit.

## Suggested skills

- `implement`: continue verification, review, issue evidence, and commit workflow.
- `diagnosing-bugs`: use for Nacos v3 API/configuration or Java bootstrap failures.
- `tdd`: add/repair public HTTP/SSE or persistence behavior tests before fixes.
- `code-review`: rerun parallel Standards and Spec reviews before committing.

## Safety

- Do not modify or delete `.claude/`.
- Do not run migrations/schema scripts or write Nacos configuration without explicit user authorization.
- Do not claim system acceptance, browser verification, or exact Yarn build success unless actually run.
- Redact all connection credentials, tokens, passwords, and signing keys in future handoffs.
