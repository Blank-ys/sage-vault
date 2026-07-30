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

Set `SAGE_VAULT_GATEWAY_URL`, `SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN`, and `SAGE_VAULT_GENERAL_USER_TOKEN`, then run individual tests:

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

All tests send browser-equivalent traffic only through Gateway. They never connect directly to Python or private database tables.
