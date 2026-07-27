INSERT INTO sys_menu (
    menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
    menu_type, visible, status, perms, icon, create_by, create_time, remark
) VALUES (
    '企业文档', 0, 6, 'sage/enterprise-documents', 'features/enterprise-documents/pages/ManagementPage',
    '', 'EnterpriseDocumentManagement', 1, 0, 'C', '0', '0', 'sage:document:manage', 'documentation',
    'admin', CURRENT_TIMESTAMP, '仅知识管理员可见'
);

SET @document_menu_id = LAST_INSERT_ID();

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_id, @document_menu_id FROM sys_role WHERE role_key = 'knowledge_admin';
