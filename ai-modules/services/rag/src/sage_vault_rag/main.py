from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.transport.http.app import create_app

app = create_app(Settings())  # type: ignore[call-arg]
