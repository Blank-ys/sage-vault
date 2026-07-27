from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    signing_key: str
    replay_window_seconds: int = 60
    nacos_server_address: str
    service_ip: str = "127.0.0.1"
    service_port: int = 8000
    service_name: str = "sage-vault-rag"
    model_config = SettingsConfigDict(env_prefix="SAGE_VAULT_RAG_", env_file=".env")
