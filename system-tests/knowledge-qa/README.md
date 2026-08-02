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

Set `SAGE_VAULT_GATEWAY_URL`, `SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN`, and `SAGE_VAULT_GENERAL_USER_TOKEN`, then run individual tests. `test_conversation_history_and_ownership.py` and `test_user_feedback_submission_and_consent.py` additionally need `SAGE_VAULT_SECOND_USER_TOKEN` for a *different* general user, and MySQL must also have `007_schema.sql` applied. `test_user_feedback_submission_and_consent.py` additionally requires `008_schema.sql`.

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

## test_user_feedback_submission_and_consent.py

Verifies user feedback submission and consent (issue 08a): an anonymous request cannot submit feedback, a submission without explicit `consentToShare` is refused with `FEEDBACK_CONSENT_REQUIRED` (410022) and leaves no trace, a category outside the closed set is refused with `FEEDBACK_CATEGORY_INVALID` (410024), a second general user cannot submit feedback on another user's answer (`FEEDBACK_FORBIDDEN` 410021), the owner's consented submission succeeds without exposing admin-only fields, conversation history reports `feedbackSubmitted=true` afterwards, a repeated submission is refused with `FEEDBACK_ALREADY_SUBMITTED` (410023), and deleting the conversation removes the feedback together with the answer.

```powershell
python -m unittest system-tests/knowledge-qa/test_user_feedback_submission_and_consent.py -v
```

## test_admin_feedback_diagnostics_and_privacy.py

Verifies the administrator feedback queue and its privacy boundary (issue 08b): a logged-in general user is refused (403) on the queue, on a feedback detail, and on the resolve endpoint; a new feedback lands in the `PENDING` queue; the detail returns the question and answer the user consented to share plus the request ID; an answer that never received feedback has no administrator entry point at all and never appears in the queue; resolving stores the internal note and moves the item to `RESOLVED`; the internal note is not echoed back into the submitting user's history; a resolved item can be reopened; and deleting the conversation removes the shared content from the administrator side (`FEEDBACK_NOT_FOUND` 410027).

Requires `009_seed.sql` for the `sage:feedback:manage` menu grant, and `SAGE_VAULT_SECOND_USER_TOKEN`.

Retrieval diagnostics (chunk identifiers, scores, stage durations) are intentionally not asserted here: their cross-language collection is owned by issue 11c. The detail response already carries `retrievalDiagnostics` and `stageDurations`, which stay empty until 11c lands.

```powershell
python -m unittest system-tests/knowledge-qa/test_admin_feedback_diagnostics_and_privacy.py -v
```

## test_cascade_delete_failure_and_retry.py

Verifies cascade-delete failure handling, idempotent retry, and concurrency safety (issue 09b): a cleanup failure surfaces as `DELETE_FAILED` carrying a diagnosable reason prefixed with the failing stage (向量清理 / 原文件清理 / 文档记录清理), the failed knowledge base is never treated as available (absent from the available list, rejected for new conversations), a `DELETE_FAILED` knowledge base is read-only except for retry (still listable and viewable, but renaming is refused with `KNOWLEDGE_BASE_STATE_CONFLICT` 410029 and uploads are refused), retry is idempotent (repeated retries only return to `DELETING`), and cleanup never touches a bystander knowledge base (its documents survive, it stays writable and can still start conversations).

Failure injection is environment-owned rather than a switch in business code: make the knowledge base's external storage (MinIO or Milvus) unavailable so the real cleanup chain fails, then set `SAGE_VAULT_CLEANUP_FAILURE_INJECTED=1`. The three failure-path tests skip without it. To additionally prove that a retry runs to completion, restore the storage and re-run with `SAGE_VAULT_CLEANUP_FAILURE_RECOVERED=1`; `test_cascade_delete_never_touches_other_knowledge_bases` needs neither variable and runs against a healthy environment.

```powershell
python -m unittest system-tests/knowledge-qa/test_cascade_delete_failure_and_retry.py -v
```

All tests send browser-equivalent traffic only through Gateway. They never connect directly to Python or private database tables.
