-- 增长与风控两域的权限配置种子（P-9 / P-16.2，配合 V120/V121）。
--
-- **生成方式**：`node ops-web/scripts/gen-perm-seed.mjs` 全量重生成，
-- 从中取出 OPS_GROWTH / OPS_RISK 两个功能及其功能点与角色授权，其余一行不动。
-- 真源是 nav.ts × perm-map.ts × Perms.java 三处，不手写。
--
-- **为什么需要它**：`Perms.java` 里加了六个 risk:* 与四个 growth:* 码，但判权的主路径
-- 是读库（sys_role_point → sys_function_point.perm_code），`ROLE_PERMS` 只是回落表。
-- 只改 Java 不落库的表现是：**页面在、端点在、对应岗位点进去一片 403** ——
-- 而 `OpsPermConfigFlowTest.dbConfigMatchesHardcoded` 正是为此存在的守卫，
-- 它当场报了 CAMPAIGN_OPS 库与代码不一致。改源码不重跑生成器 = 没改。
--
-- 写成可重入形式：迁移中途失败、本地库来回切分支都会让它再跑一次。


INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
SELECT 'OPS_GROWTH', '增长与归因', 'OPS', 'TrendingUp', '/growth', 100, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function x WHERE x.function_code='OPS_GROWTH');

INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
SELECT 'OPS_RISK', '风控', 'OPS', 'ShieldAlert', '/risk', 160, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function x WHERE x.function_code='OPS_RISK');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_GROWTH', 'OPS_GROWTH', '归因规则', '归因引擎', '/growth', 'growth:attribution:read', 'growth:attribution:read', 'IMPLEMENTED', 1, 'P-9.1', 'MENU', 10, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_GROWTH');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_GROWTH__TAB_TRACES', 'OPS_GROWTH', '归因链路审计', '归因引擎', '/growth?tab=traces', 'growth:attribution:read', 'growth:attribution:read', 'IMPLEMENTED', 1, 'P-9.1', 'MENU', 20, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_GROWTH__TAB_TRACES');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_GROWTH__TAB_FISSION', 'OPS_GROWTH', '邀请有礼配置', '裂变活动', '/growth?tab=fission', 'growth:fission:update', 'growth:fission:update', 'IMPLEMENTED', 1, 'P-9.2', 'MENU', 30, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_GROWTH__TAB_FISSION');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_RISK', 'OPS_RISK', '风险事件', '识别', '/risk', 'risk:rule:read', 'risk:rule:read', 'IMPLEMENTED', 1, 'P-16.2', 'MENU', 10, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_RISK');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_RISK__TAB_BLACKLIST', 'OPS_RISK', '黑名单与申诉', '处置', '/risk?tab=blacklist', 'risk:blacklist:update', 'risk:blacklist:update', 'IMPLEMENTED', 1, 'P-16.2', 'MENU', 20, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_RISK__TAB_BLACKLIST');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_RISK__TAB_RULES', 'OPS_RISK', '拦截规则配置', '处置', '/risk?tab=rules', 'risk:rule:update', 'risk:rule:update', 'IMPLEMENTED', 1, 'P-16.2', 'MENU', 30, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_RISK__TAB_RULES');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__GROWTH_ATTRIBUTION_UPDATE', 'OPS_GROWTH', 'growth:attribution:update', '仅后端', NULL, NULL, 'growth:attribution:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 920, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__GROWTH_ATTRIBUTION_UPDATE');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__GROWTH_FISSION_READ', 'OPS_GROWTH', 'growth:fission:read', '仅后端', NULL, NULL, 'growth:fission:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 921, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__GROWTH_FISSION_READ');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__RISK_BLACKLIST_READ', 'OPS_RISK', 'risk:blacklist:read', '仅后端', NULL, NULL, 'risk:blacklist:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 927, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__RISK_BLACKLIST_READ');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__RISK_EVENT_HANDLE', 'OPS_RISK', 'risk:event:handle', '仅后端', NULL, NULL, 'risk:event:handle', 'IMPLEMENTED', 0, NULL, 'ACTION', 928, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__RISK_EVENT_HANDLE');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__RISK_EVENT_READ', 'OPS_RISK', 'risk:event:read', '仅后端', NULL, NULL, 'risk:event:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 929, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__RISK_EVENT_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_GROWTH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_GROWTH');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_GROWTH__TAB_TRACES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_GROWTH__TAB_FISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_GROWTH__TAB_FISSION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_RISK', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_RISK');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_RISK__TAB_BLACKLIST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_RISK__TAB_BLACKLIST');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_RISK__TAB_RULES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_RISK__TAB_RULES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__GROWTH_ATTRIBUTION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__GROWTH_ATTRIBUTION_UPDATE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__GROWTH_FISSION_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__GROWTH_FISSION_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__RISK_BLACKLIST_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__RISK_BLACKLIST_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__RISK_EVENT_HANDLE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__RISK_EVENT_HANDLE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__RISK_EVENT_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__RISK_EVENT_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_GROWTH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_GROWTH');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_GROWTH__TAB_TRACES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_GROWTH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_GROWTH');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_GROWTH__TAB_TRACES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_GROWTH__TAB_FISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_GROWTH__TAB_FISSION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'ACT__GROWTH_ATTRIBUTION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__GROWTH_ATTRIBUTION_UPDATE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'ACT__GROWTH_FISSION_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__GROWTH_FISSION_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'OPS_RISK', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='OPS_RISK');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'OPS_RISK__TAB_BLACKLIST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='OPS_RISK__TAB_BLACKLIST');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'OPS_RISK__TAB_RULES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='OPS_RISK__TAB_RULES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'ACT__RISK_BLACKLIST_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__RISK_BLACKLIST_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'ACT__RISK_EVENT_HANDLE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__RISK_EVENT_HANDLE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'ACT__RISK_EVENT_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__RISK_EVENT_READ');
