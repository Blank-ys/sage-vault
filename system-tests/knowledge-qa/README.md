# Empty knowledge base system acceptance

This black-box test requires the real Gateway, knowledge-base Java service, Python RAG service, Nacos, Redis, and MySQL initialized with `backend/ruoyi-kb-management/sql/001_schema.sql` and `002_seed.sql`.

The Gateway's environment-owned `ruoyi-gateway-<profile>.yml` Nacos configuration must include this route. Merge it into that profile's existing `spring.cloud.gateway.server.webflux.routes` list; this repository intentionally does not publish environment addresses, credentials, or a replacement Nacos document.

```yaml
- id: ruoyi-kb-management
  uri: lb://ruoyi-kb-management
  predicates:
    - Path=/ruoyi-kb-management/**
  filters:
    - StripPrefix=1
```

Set `SAGE_VAULT_GATEWAY_URL`, `SAGE_VAULT_KNOWLEDGE_ADMIN_TOKEN`, and `SAGE_VAULT_GENERAL_USER_TOKEN`, then run:

```powershell
python -m unittest system-tests/knowledge-qa/test_empty_knowledge_base.py -v
```

The test sends browser-equivalent traffic only through Gateway. It never connects directly to Python or private database tables.
