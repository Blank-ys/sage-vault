-- 反馈处理菜单：仅知识管理员可见，权限标识与 AdminFeedbackController 一致。
INSERT INTO sys_menu (
    menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
    menu_type, visible, status, perms, icon, create_by, create_time, remark
) VALUES (
    '问答反馈', 0, 7, 'sage/feedback', 'features/feedback/pages/ManagementPage',
    '', 'FeedbackManagement', 1, 0, 'C', '0', '0', 'sage:feedback:manage', 'message',
    'admin', CURRENT_TIMESTAMP, '仅知识管理员可见，只能查看用户已授权共享的问答'
);

SET @feedback_menu_id = LAST_INSERT_ID();

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_id, @feedback_menu_id FROM sys_role WHERE role_key = 'knowledge_admin';
