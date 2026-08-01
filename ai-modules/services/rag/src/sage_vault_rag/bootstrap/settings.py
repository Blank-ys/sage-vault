from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # 服务注册与安全
    signing_key: str
    replay_window_seconds: int = 60
    nacos_server_address: str
    service_ip: str = "127.0.0.1"
    service_port: int = 8000
    service_name: str = "sage-vault-rag"

    # Java 回调
    java_callback_url: str = ""
    java_callback_signing_key: str = ""
    java_cleanup_callback_url: str = ""

    # Milvus
    milvus_host: str = "127.0.0.1"
    milvus_port: int = 19530
    milvus_collection_name: str = "sage_vault_chunks"
    milvus_vector_dim: int = 1024

    # 本地嵌入模型
    embedding_model_path: str
    embedding_profile: str = "cpu-dev"  # 或 "gpu"
    embedding_batch_size: int = 1
    embedding_max_length: int = 8192
    embedding_max_concurrent_requests: int = 1
    embedding_max_queue_size: int = 0

    # 文档切块
    chunk_size: int = 512
    chunk_overlap: int = 64

    # 检索与回答
    retrieval_top_k: int = 5
    retrieval_refusal_threshold: float = 1.0
    answer_delta_length: int = 5
    # delta 之间的最小间隔，保证生成期间事件循环可处理取消命令
    answer_delta_interval_seconds: float = 0.05

    # 测试模式故障注入（仅供系统验收测试使用，生产环境留空）
    test_failure_flag_file: str = ""

    model_config = SettingsConfigDict(env_prefix="SAGE_VAULT_RAG_", env_file=".env")
