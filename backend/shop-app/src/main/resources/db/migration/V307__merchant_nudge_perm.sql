-- 主动触达商家的权限码（merchant:merchant:nudge，M2）。
--
-- **为什么单独一个码**：它以平台的名义往商家的收件箱里写一条消息，
-- 而读档案（merchant:merchant:read）不会在对方那里留下任何东西。
-- 也没有挂在 merchant:merchant:ban 下面 —— 那是封店；「你有商品还没上架」
-- 与「封店」之间隔着一整个量级，而缺的正是中间这一档。
-- 挂在封店权限下等于「只有能封店的人才提醒得了商家」。
--
-- 是**页面内动作**不是菜单，所以点类型是 ACTION、无 href、ui_ready = 0
-- （照 V74 建仅后端动作点的那批的写法）。
--
-- 授权：超管 + BD。链条画像指出「今天该找哪家」之后，打电话发消息的人就是 BD。
-- 不给 GOODS_OPS —— 商品运营管的是品，不是商家关系。
-- Perms.java 的 ROLE_PERMS 也加了同一行：**少一头 OpsPermConfigFlowTest 就红**，
-- 而少的那一头决定症状是「按钮点了 403」还是「按钮压根不出现」。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MERCHANT_MERCHANT_NUDGE', 'OPS_MERCHANT', '主动触达商家', '经营诊断', NULL, 'merchant:merchant:nudge', 'merchant:merchant:nudge', 'IMPLEMENTED', 0, 'P-11.1', 'ACTION', 91, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MERCHANT_MERCHANT_NUDGE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_MERCHANT_NUDGE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_MERCHANT_NUDGE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'ACT__MERCHANT_MERCHANT_NUDGE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__MERCHANT_MERCHANT_NUDGE');
