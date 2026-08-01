# Knowledge QA system acceptance tests

Black-box tests that exercise the full browser → Gateway → Java kb-management → Python RAG → Milvus path. They require the real Gateway, knowledge-base Java service, Python RAG service, Nacos, Redis, and MySQL initialized with `backend/ruoyi-kb-management/sql/001_schema.sql` and `002_seed.sql`.

The Gateway's environment-owned `ruoyi-gateway-<profile>.yml` Nacos configuration must include this route. Merge it into that profile's existing `spring.cloud.gateway.server.webflux.routes` list; this repository intentionally does not publish environment addresses, credentials, or a replacement Nacos document.

```yaml
- id: ruoyi-kb-management
  uri: lb://ruoyi-kb-management
  predicates:
    - Path=/ruoyi-kb-management/**
  filters:
    - StripPrefix=1
```

Set `SAGE_VAULT_GATEWAY_URL`, `SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN`, and `SAGE_VAULT_GENERAL_USER_TOKEN`, then run individual tests. `test_conversation_history_and_ownership.py` additionally needs `SAGE_VAULT_SECOND_USER_TOKEN` for a *different* general user, and MySQL must also have `007_schema.sql` applied.

## test_empty_knowledge_base.py

Verifies the empty-knowledge-base refusal flow (issue 02): anonymous rejection, KB CRUD, duplicate name guard, and SSE refusal message when no available documents exist.

```powershell
python -m unittest system-tests/knowledge-qa/test_empty_knowledge_base.py -v
```

## test_retry_and_atomic_publication.py

Verifies document failure retry and atomic publication (issue 05): injects a parse failure via an empty Markdown file, polls document status through PROCESSING → FAILED, confirms no partial content is retrievable during failure or retry, validates that retry preserves document identity without creating duplicates, and asserts that retry on an AVAILABLE document is rejected with `DOCUMENT_STATE_CONFLICT` (410014).

```powershell
python -m unittest system-tests/knowledge-qa/test_retry_and_atomic_publication.py -v
```

## test_conversation_history_and_ownership.py

Verifies conversation organization, permanent history, and ownership cascade delete (issue 07a): the first question becomes the default title, a renamed title survives follow-up questions, a conversation keeps multiple independent question/answer records in asked order, history never leaks into a later answer, a second general user can neither list, read, rename, nor delete another user's conversation (`CONVERSATION_FORBIDDEN` 410004), and deleting a conversation removes it together with its question bodies (`CONVERSATION_NOT_FOUND` 410003 afterwards).

```powershell
python -m unittest system-tests/knowledge-qa/test_conversation_history_and_ownership.py -v
```

## test_single_user_serialization_and_state_machine.py

Verifies single-user serialization and the answer state machine (issue 07b): different users can each ask concurrently without blocking; the answer-state endpoint `GET /{id}/answers/{generationId}` reports a `REFUSED` terminal correctly (`ready=true`, `status=REFUSED`) and the refused terminal does not count as in-progress (the conversation can be asked again); a second user reading another user's answer state is rejected with `CONVERSATION_FORBIDDEN` (410004). The hard concurrency-conflict assertion (`CONVERSATION_CONCURRENCY_CONFLICT` 410016 on a second in-progress question) requires retrievable documents so a `STARTED` answer persists long enough to race; set `SAGE_VAULT_HAS_RETRIEVABLE_DOCS=1` to enable it. Without retrievable docs every answer is refused synchronously (sub-second terminal), so that window cannot be reproduced at the system level — that path is covered deterministically by the Java unit tests and live MySQL integration tests.

```powershell
python -m unittest system-tests/knowledge-qa/test_single_user_serialization_and_state_machine.py -v
```

## test_stream_stop_and_best_effort_cancel.py

Verifies stream stop and best-effort cancellation (issue 07c): stopping another user's answer is rejected with `CONVERSATION_FORBIDDEN` (410004) without rewriting the terminal state, and stopping an answer that already reached a terminal state is rejected with `ANSWER_NOT_STOPPABLE` (410020) while the existing `REFUSED` terminal stays intact.

The hard stop assertions — the stream ends with a `stopped` event, the answer is persisted as `STOPPED` with the partial text already streamed, a repeated stop is refused without rewriting that terminal, the conversation gate is released afterwards, and a plain disconnect converges to `UNFINISHED` instead of `STOPPED` — require a knowledge base with retrievable documents so a `STARTED` answer stays in flight long enough to be stopped. Set `SAGE_VAULT_HAS_RETRIEVABLE_DOCS=1` and `SAGE_VAULT_RETRIEVABLE_KB_ID=<id>` to enable them. Without retrievable docs every answer is refused synchronously, so that window cannot be reproduced at the system level; those paths are covered deterministically by the Java unit tests, the live MySQL mapper integration test, and the Python contract tests.

```powershell
python -m unittest system-tests/knowledge-qa/test_stream_stop_and_best_effort_cancel.py -v
```

All tests send browser-equivalent traffic only through Gateway. They never connect directly to Python or private database tables.
