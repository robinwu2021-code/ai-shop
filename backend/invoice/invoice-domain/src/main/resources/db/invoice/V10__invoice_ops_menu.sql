-- 运营端「电子发票」菜单（TDD-发票模块 §18）。放在发票自己的迁移序列里：打包排除发票模块（-Dinvoice.exclude）时
-- 这一页连菜单点都不存在，不会出现一个点进去 404 的入口。
--
-- 权限复用 finance:invoice:read（看）/ finance:invoice:verify（核验、领号、重传、查单），与「买家开票申请」同一批财务人员；
-- 可见角色同 V253 的发票类页签：SUPER_ADMIN、FINANCE（AUDITOR 不给：页上有写动作）。
-- sort 取财务组未用的 58；既有行一行不动。

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_FINANCE__E_INVOICES', 'OPS_FINANCE', '电子发票', '应付与发票',
       '/e-invoices', 'finance:invoice:read', 'finance:invoice:read',
       'IMPLEMENTED', 1, 'P-12.2', 'MENU', 58, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code = 'OPS_FINANCE__E_INVOICES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_FINANCE__E_INVOICES', 'OPS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_point WHERE role_code = 'SUPER_ADMIN'
                  AND point_code = 'OPS_FINANCE__E_INVOICES' AND end_code = 'OPS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__E_INVOICES', 'OPS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_point WHERE role_code = 'FINANCE'
                  AND point_code = 'OPS_FINANCE__E_INVOICES' AND end_code = 'OPS');
