-- 新增功能点「菜单顺序」（/iam?tab=menu）。
--
-- 调序此前寄居在角色抽屉的功能矩阵里 —— 而**调序是全局的，与角色无关**。
-- 放在那里等于暗示「这个顺序属于这个角色」，且预置角色的抽屉写着「只读」，
-- 旁边却有能点的上移下移，自相矛盾。现在单独成页。
--
-- 复用 iam:role:grant，**不新增权限码**：能配权限的人才该动菜单结构。
--
-- 全部写成显式 VALUES —— 教训见 V76：H2 测试夹具回放不了 SELECT 式插入，
-- 却能回放 DELETE，于是夹具会停在半应用状态而只在全量单测时显形。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM__TAB_MENU', 'OPS_IAM', '菜单顺序', '账号', '/iam?tab=menu', 'iam:role:grant', 'iam:role:grant', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 30, NOW(), NOW());

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_IAM__TAB_MENU', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_IAM__TAB_MENU');
