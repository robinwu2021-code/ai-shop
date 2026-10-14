-- 「区划补录」菜单点 + 裁决权限（community:region:update）。
--
-- **生成方式**：与 V99 / V153 同法 —— `ops-web/scripts/gen-perm-seed.mjs` 是全量重生成器，
-- 不能直接落成增量迁移。跑一遍，从输出里**逐字取出**本次新增的这 6 行，其余一行不动。
--
-- 唯一的改动是 `sort`：生成器按叶子在 nav.ts 里的位置全表重排，本次它给 30，
-- 而库里 30 是「自提点」。跟着写会撞号（sort 不是唯一键，撞了不报错，
-- 只是两个 tab 的先后从此靠数据库返回顺序决定 —— 那是不稳定的）。
-- 取 50 接在「临时点监控」（40）之后；ACTION 点取 912，接在
-- ACT__COMMUNITY_REGION_READ（911）之后。
--
-- 为什么菜单点用 read 而裁决动作用 update：读区划是所有运营挑覆盖范围时的前置，
-- 几乎人人有；而通过一条补录会让它对**全平台商家**可见，一个错别字污染的是共享的那棵树。
-- 两者出错的后果不在一个量级，所以 tab 进得去、按钮按 can() 显隐。
--
-- 可重入形式（SELECT … FROM DUAL WHERE NOT EXISTS）：裸 VALUES 撞唯一键就是 1062，
-- 而重跑不是异常情况。⚠️ 幂等插入**只保证不重复建、不保证值是对的** ——
-- 以后改这几行的任何一个值，都要另写 UPDATE。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMUNITY__TAB_REGIONS', 'OPS_COMMUNITY', '区划补录', '社区网格', '/communities?tab=regions', 'community:region:read', 'community:region:read', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 50, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMUNITY__TAB_REGIONS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__COMMUNITY_REGION_UPDATE', 'OPS_COMMUNITY', 'community:region:update', '页面内操作', NULL, 'community:region:update', 'community:region:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 912, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__COMMUNITY_REGION_UPDATE');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMUNITY__TAB_REGIONS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMUNITY__TAB_REGIONS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__COMMUNITY_REGION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__COMMUNITY_REGION_UPDATE');

-- 社区运营：这批人本来就在审商家提报的小区，区划补录是同一类事
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_REGIONS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_COMMUNITY__TAB_REGIONS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'ACT__COMMUNITY_REGION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='ACT__COMMUNITY_REGION_UPDATE');
