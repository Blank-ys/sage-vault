-- Knowledge administrator role and permission. The QA workspace is a constant authenticated route.
INSERT INTO sys_role (
    role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly,
    status, del_flag, create_by, create_time, remark
) VALUES ('知识管理员', 'knowledge_admin', 3, '1', 1, 1, '0', '0', 'admin', CURRENT_TIMESTAMP, 'Sage Vault V1 知识管理员');

SET @knowledge_admin_role_id = LAST_INSERT_ID();

INSERT INTO sys_menu (
    menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
    menu_type, visible, status, perms, icon, create_by, create_time, remark
) VALUES (
    '知识库管理', 0, 5, 'sage/knowledge-bases', 'features/knowledge-bases/pages/ManagementPage',
    '', 'KnowledgeBaseManagement', 1, 0, 'C', '0', '0', 'sage:knowledge-base:manage', 'list',
    'admin', CURRENT_TIMESTAMP, '仅知识管理员可见'
);

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (@knowledge_admin_role_id, LAST_INSERT_ID());
