-- 运营端「位置分布」菜单落库。
--
-- 菜单不是 nav.ts 说了算：标签、分组、可见性都来自 sys_function_point / sys_role_point。
-- 只改 nav.ts 的话，本地 mock 下看得到，**接上真实后端就没有**。
--
-- sort 取 70：60 是上一版刚插的「坐标健康」，同样插在末尾，既有行一行不动。
--
-- 角色授权逐行写 INSERT ... SELECT ... WHERE NOT EXISTS：
-- gen-test-schema.py 不认 CROSS JOIN，写成一条会让 H2 那份 schema 里一条授权都没有。
INSERT INTO sys_function_point
  (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
   backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS_COMMUNITY', '位置分布', '社区网格',
       '/communities?tab=distribution', 'community:community:read', 'community:community:read',
       'IMPLEMENTED', 1, 'P-2.1', 'MENU', 70, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'AUDITOR', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='AUDITOR' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'ANALYST', 'OPS_COMMUNITY__TAB_DISTRIBUTION', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='ANALYST' AND x.point_code='OPS_COMMUNITY__TAB_DISTRIBUTION');
