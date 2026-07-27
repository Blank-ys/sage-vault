CREATE TABLE sv_enterprise_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    size BIGINT NOT NULL,
    error_message VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sv_enterprise_document_kb_normalized_name (kb_id, normalized_name),
    KEY idx_sv_enterprise_document_kb_id (kb_id),
    CONSTRAINT fk_sv_enterprise_document_knowledge_base
        FOREIGN KEY (kb_id) REFERENCES sv_knowledge_base (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
