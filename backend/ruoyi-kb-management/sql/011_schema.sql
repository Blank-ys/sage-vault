-- 11: 检索/生成阶段诊断子表
-- 一次回答产生多条片段诊断记录，承载片段标识、分数与阶段耗时，不含片段正文；
-- qa_record_id 关联 sv_qa_record，会话删除时由父表级联清理。

CREATE TABLE sv_qa_retrieval_diagnostic (
    id BIGINT NOT NULL AUTO_INCREMENT,
    qa_record_id BIGINT NOT NULL,
    generation_id VARCHAR(100) NOT NULL,
    document_id VARCHAR(100),
    chunk_id VARCHAR(100),
    score DECIMAL(10,6),
    stage VARCHAR(32) NOT NULL COMMENT '阶段：embedding/retrieval/generation',
    duration_ms BIGINT COMMENT '该阶段耗时（毫秒）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sv_qa_retrieval_diagnostic_qa_record (qa_record_id),
    KEY idx_sv_qa_retrieval_diagnostic_generation (generation_id),
    CONSTRAINT fk_sv_qa_retrieval_diagnostic_qa_record
        FOREIGN KEY (qa_record_id) REFERENCES sv_qa_record (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
