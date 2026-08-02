-- 09a: 知识库级联删除
-- 知识库删除成功后活动记录被移除，但历史会话与问答必须继续可读；
-- 因此解除会话对知识库的外键，并为知识库补充清理进度与失败诊断字段。

-- 会话不再强引用知识库：知识库活动记录删除后，历史会话与问答仍可读，
-- 读取时知识库缺失即表示"知识库已删除"。
ALTER TABLE sv_conversation
    DROP FOREIGN KEY fk_sv_conversation_knowledge_base;

-- 保留按知识库检索会话的能力（原索引由外键隐式创建，外键移除后需显式保留）
ALTER TABLE sv_conversation
    ADD KEY idx_sv_conversation_knowledge_base (knowledge_base_id);

-- 删除失败原因：进入 DELETE_FAILED 时展示给知识管理员，空串表示无失败
ALTER TABLE sv_knowledge_base
    ADD COLUMN error_message VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '删除失败原因，空串表示无失败';

-- 清理尝试次数：每轮后台级联清理推进一次，用于残留检测与重试诊断
ALTER TABLE sv_knowledge_base
    ADD COLUMN cleanup_attempt INT NOT NULL DEFAULT 0 COMMENT '级联清理尝试次数';

-- 按状态扫描待清理知识库
ALTER TABLE sv_knowledge_base
    ADD KEY idx_sv_knowledge_base_status (status);
