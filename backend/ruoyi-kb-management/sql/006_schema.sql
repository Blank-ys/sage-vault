-- 06b: 删除清理失败诊断与幂等重试
-- 新增 CLEANUP_FAILED 终态、cleanup_phase 诊断字段、cleanup_attempt 重试计数与清理任务追踪表

-- 扩展文档表：新增清理阶段记录
ALTER TABLE sv_enterprise_document
    ADD COLUMN cleanup_phase VARCHAR(32) NULL DEFAULT NULL COMMENT '清理失败阶段：MILVUS_CLEANUP / MINIO_CLEANUP';

-- 扩展文档表：新增清理重试计数（用于残留检测 FAILSAFE 阈值判断）
ALTER TABLE sv_enterprise_document
    ADD COLUMN cleanup_attempt INT NOT NULL DEFAULT 0 COMMENT '清理重试次数，达到阈值仍未完成则由 FAILSAFE 置为 CLEANUP_FAILED';

-- 新增清理任务表：与索引任务共用同一张表，通过 task_type 区分
ALTER TABLE sv_document_indexing_task
    ADD COLUMN task_type VARCHAR(20) NOT NULL DEFAULT 'INDEXING' COMMENT '任务类型：INDEXING / CLEANUP';
