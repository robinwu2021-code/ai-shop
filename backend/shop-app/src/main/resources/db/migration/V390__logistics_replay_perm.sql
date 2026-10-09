-- 「物流重放」页面内操作点（TDD-物流模块 O3：POST /ops/shipments/{no}/replay）。
--
-- 单独一个码 fulfillment:logistics:replay，不并进 fulfillment:rule:update：
-- 重放会真去调渠道，消耗快递100 每单每月 4 次的订阅额度。
-- 授给 SUPER_ADMIN 与 COMMUNITY_OPS —— 与 Perms.ROLE_PERMS 一致（社区运营持有全部履约码）。
-- 超管是通配角色，但配置表仍要逐点关联：少了这一行，权限配置界面里看不到这一项。
--
-- function_code = OPS_ORDER：履约菜单已并进订单（OPS_FULFILLMENT 那一组整体改挂 OPS_ORDER）。
-- sort 941：接在现有页面内操作点（最大 940）之后。可重入写法（WHERE NOT EXISTS），同 V328。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
                                backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__FULFILLMENT_LOGISTICS_REPLAY', 'OPS_ORDER', 'fulfillment:logistics:replay', '页面内操作', NULL,
       'fulfillment:logistics:replay', 'fulfillment:logistics:replay', 'IMPLEMENTED', 1, NULL, 'ACTION', 941, NOW(), NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code = 'ACT__FULFILLMENT_LOGISTICS_REPLAY');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__FULFILLMENT_LOGISTICS_REPLAY', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = 'SUPER_ADMIN' AND x.point_code = 'ACT__FULFILLMENT_LOGISTICS_REPLAY');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'ACT__FULFILLMENT_LOGISTICS_REPLAY', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = 'COMMUNITY_OPS' AND x.point_code = 'ACT__FULFILLMENT_LOGISTICS_REPLAY');
