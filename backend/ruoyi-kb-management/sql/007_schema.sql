-- 07a: 会话历史与归属
-- 会话补齐标题与最近活跃时间，问答记录支持按会话级联删除

-- 会话标题：为空表示尚未由首个提问生成默认标题
ALTER TABLE sv_conversation
    ADD COLUMN title VARCHAR(200) NOT NULL DEFAULT '' COMMENT '会话标题，空串表示尚未生成默认标题';

-- 会话最近活跃时间：新建提问或改名时推进，用于列表倒序
ALTER TABLE sv_conversation
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近活跃时间';

-- 会话列表按归属人 + 最近活跃时间倒序翻页
ALTER TABLE sv_conversation
    ADD KEY idx_sv_conversation_user_updated (user_id, updated_at);

-- 删除会话时级联清除该会话下的问答记录正文
ALTER TABLE sv_qa_record
    DROP FOREIGN KEY fk_sv_qa_record_conversation;

ALTER TABLE sv_qa_record
    ADD CONSTRAINT fk_sv_qa_record_conversation
        FOREIGN KEY (conversation_id) REFERENCES sv_conversation (id) ON DELETE CASCADE;

-- 会话内问答历史按时间正序读取
ALTER TABLE sv_qa_record
    ADD KEY idx_sv_qa_record_conversation_created (conversation_id, created_at);
