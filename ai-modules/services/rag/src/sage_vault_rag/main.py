from sage_vault_rag.adapters.nacos.registration import NacosRegistration
from sage_vault_rag.bootstrap.factories import build_dependencies
from sage_vault_rag.bootstrap.settings import Settings
from sage_vault_rag.transport.http.app import create_app

_settings = Settings()  # type: ignore[call-arg]
app = create_app(
    _settings,
    dependencies=build_dependencies(_settings),
    registration=NacosRegistration(_settings),
)
