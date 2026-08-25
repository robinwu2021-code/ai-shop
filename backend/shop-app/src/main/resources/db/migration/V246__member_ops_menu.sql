-- 运营端「会员与人档」菜单与权限点（P8）。
--
-- **为什么必须有这支迁移**：运营端的菜单不在 `nav.ts` 里，在库里
-- （`sys_function` / `sys_function_point` / `sys_role_point`）。只改前端的话，
-- **mock 下菜单出现、接真后端就消失** —— 页面、路由、代码全在，就是点不到。
--
-- **生成方式**：跑 `ops-web/scripts/gen-perm-seed.mjs`（全量重生成器），
-- 从输出里**逐字取出**本次新增的那几行，其余一行不动 —— 与 V99 同一手法。
-- point_code / perm_code / matrix_code 都与生成器一致，下次全量重生成不会漂移。
--
-- 写成 `SELECT … FROM DUAL WHERE NOT EXISTS` 而不是裸 VALUES：撞唯一键就是 1062，
-- 而重跑不是异常情况 —— 迁移中途失败、本地库来回切分支都会让它再跑一次。

-- ── 一级功能：会员与人档 ────────────────────────────────────────────────
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
SELECT 'OPS_MEMBER', '会员与人档', 'OPS', 'UserCheck', '/members', 90, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function x WHERE x.function_code='OPS_MEMBER');

-- ── 菜单点三条 ─────────────────────────────────────────────────────────
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MEMBER', 'OPS_MEMBER', '会员名单', '会员', '/members', 'member:member:read', 'member:member:read', 'IMPLEMENTED', 1, 'P-7.4', 'MENU', 10, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MEMBER');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MEMBER__TAB_PERSONS', 'OPS_MEMBER', '人档', '会员', '/members?tab=persons', 'member:person:read', 'member:person:read', 'IMPLEMENTED', 1, 'P-7.4', 'MENU', 20, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MEMBER__TAB_PERSONS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MEMBER__TAB_REACH', 'OPS_MEMBER', '触达健康度', '会员', '/members?tab=reach', 'member:member:read', 'member:member:read', 'IMPLEMENTED', 1, 'P-7.4', 'MENU', 30, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MEMBER__TAB_REACH');

-- ── 页面内操作两条 ─────────────────────────────────────────────────────
-- 查看完整手机号与合并人档都是**页面内动作**，不进菜单。
-- 它们各自一个权限码：看号能把后四位还原成真实号码，合并不可逆 ——
-- 都不该跟着「能看会员名单」一起给出去。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MEMBER_PERSON_MERGE', 'OPS_MEMBER', 'member:person:merge', '页面内操作', NULL, 'member:person:merge', 'member:person:merge', 'IMPLEMENTED', 1, NULL, 'ACTION', 910, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MEMBER_PERSON_MERGE');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MEMBER_PHONE_REVEAL', 'OPS_MEMBER', 'member:phone:reveal', '页面内操作', NULL, 'member:phone:reveal', 'member:phone:reveal', 'IMPLEMENTED', 1, NULL, 'ACTION', 911, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MEMBER_PHONE_REVEAL');

-- ── 营销页的两个敞口 tab ───────────────────────────────────────────────
-- sort 取 60/70 接在现有叶子之后：**不插队**，否则要顺带改掉其后每一行的 sort，
-- 而 sys_role_point 存的是 point_code，重排本身不会错，但改动面没必要那么大。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MARKETING__TAB_PROMOCOUPONS', 'OPS_MARKETING', '券敞口', '敞口', '/marketing?tab=promoCoupons', 'marketing:coupon:read', 'marketing:coupon:read', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 60, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MARKETING__TAB_PROMOCOUPONS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MARKETING__TAB_PROMOACTIVITIES', 'OPS_MARKETING', '活动敞口', '敞口', '/marketing?tab=promoActivities', 'marketing:campaign:read', 'marketing:campaign:read', 'IMPLEMENTED', 1, 'P-7.2', 'MENU', 70, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MARKETING__TAB_PROMOACTIVITIES');

-- ── 角色授权 ───────────────────────────────────────────────────────────
-- 超管是通配角色，但库里仍逐点关联（wildcard 只是短路，配置表要能审）。
-- GOODS_OPS 是商品运营：他已经持有 marketing:* 与新加的 member:*。
-- **漏掉授权那一行的表现是静默降级** —— 菜单里没有这一项，而页面、路由、代码全在。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT r.role_code, r.point_code, 'OPS', NOW(), NOW() FROM (
  SELECT 'SUPER_ADMIN' AS role_code, 'OPS_MEMBER' AS point_code UNION ALL
  SELECT 'SUPER_ADMIN', 'OPS_MEMBER__TAB_PERSONS' UNION ALL
  SELECT 'SUPER_ADMIN', 'OPS_MEMBER__TAB_REACH' UNION ALL
  SELECT 'SUPER_ADMIN', 'ACT__MEMBER_PERSON_MERGE' UNION ALL
  SELECT 'SUPER_ADMIN', 'ACT__MEMBER_PHONE_REVEAL' UNION ALL
  SELECT 'SUPER_ADMIN', 'OPS_MARKETING__TAB_PROMOCOUPONS' UNION ALL
  SELECT 'SUPER_ADMIN', 'OPS_MARKETING__TAB_PROMOACTIVITIES' UNION ALL
  SELECT 'GOODS_OPS', 'OPS_MEMBER' UNION ALL
  SELECT 'GOODS_OPS', 'OPS_MEMBER__TAB_PERSONS' UNION ALL
  SELECT 'GOODS_OPS', 'OPS_MEMBER__TAB_REACH' UNION ALL
  SELECT 'GOODS_OPS', 'OPS_MARKETING__TAB_PROMOCOUPONS' UNION ALL
  SELECT 'GOODS_OPS', 'OPS_MARKETING__TAB_PROMOACTIVITIES' UNION ALL
  SELECT 'CAMPAIGN_OPS', 'OPS_MARKETING__TAB_PROMOCOUPONS' UNION ALL
  SELECT 'CAMPAIGN_OPS', 'OPS_MARKETING__TAB_PROMOACTIVITIES'
) r
 WHERE NOT EXISTS (
   SELECT 1 FROM sys_role_point x
    WHERE x.role_code = r.role_code AND x.point_code = r.point_code AND x.end_code = 'OPS');
