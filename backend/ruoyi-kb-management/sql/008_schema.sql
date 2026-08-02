-- 08a: 用户提交问答反馈与同意共享
-- 反馈是用户对某条问答的主动共享行为：一条问答最多一条反馈，正文随问答删除而删除

CREATE TABLE sv_qa_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    qa_id BIGINT NOT NULL COMMENT '被反馈的问答记录',
    user_id BIGINT NOT NULL COMMENT '提交反馈的用户，用于归属校验',
    category VARCHAR(32) NOT NULL COMMENT '反馈类别：WRONG_ANSWER/NO_ANSWER_FOUND/INCOMPLETE_ANSWER/OTHER',
    comment VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '用户可选说明，空串表示未填写',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '管理员处理状态：PENDING/RESOLVED',
    admin_note VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '管理员内部备注，对用户不可见',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 一条问答只允许一条反馈：重复提交由唯一键在库层兜底
    UNIQUE KEY uk_sv_qa_feedback_qa (qa_id),
    KEY idx_sv_qa_feedback_user (user_id),
    KEY idx_sv_qa_feedback_status_created (status, created_at),
    -- 用户删除问答或会话时，反馈正文随之删除，不留残留内容
    CONSTRAINT fk_sv_qa_feedback_qa_record
        FOREIGN KEY (qa_id) REFERENCES sv_qa_record (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
