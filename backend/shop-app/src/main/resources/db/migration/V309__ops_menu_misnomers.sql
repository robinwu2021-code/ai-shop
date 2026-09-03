-- 三处错位里的两处（M8）。菜单名与分组的真源在库里，只改 nav.ts 接上真后端就看不到。

-- ── ① 「类目 × 支付方式」名不副实 ────────────────────────────────────────
--
-- 它一件支付方式的事都不管。它只决定「这个类目能不能**当面付**」——
-- 四层判定（类目 → 主体资质 → 门店 → 商品）的第一层，而且是**黑名单**：
-- 没有行即放行，配一条才是禁止。
--
-- 业务上完全成立（当面付意味着平台收不到钱、没有流水、无法对账与风控，
-- 所以高风险类目该被禁 —— 类目在这里是**风控维度**）。不成立的是名字：
-- 看到「支付方式」的人会以为是配微信 / 支付宝，每个第一次看到它的人
-- 都要把页面上那段说明读一遍才明白。名字本该省掉这一步。
--
-- **只改 name，不改 href**：路由是内部键，改它要动 TAB_KEYS、清单产物，
-- 还会让已有链接失效，而这些换不来任何东西。
UPDATE sys_function_point
   SET name = '当面付禁用类目', updated_at = NOW()
 WHERE point_code = 'OPS_PRODUCT__TAB_CATEGORY_PAY_MODE';

-- ── ② 「增值包与额度」一个 tab 混了两类职责 ──────────────────────────────
--
-- 它同时管「定义档位」（planDefs / savePlanDef，一年动几次的产品决策）
-- 与「授予某商家」（grantPlan / overridePlanQuota，天天做的运营动作）。
-- 混在一屏，前者会被后者的噪声淹没 —— 而它正是分层（FREE/PRO/CHAIN）
-- 落地前必须先理清的地基。
--
-- 两条权限本来就不同：授予走 merchant:merchant:ban（处置面），
-- 改定义走 system:param:update。一个 tab 装两个码，页面只能取松的那个。
UPDATE sys_function_point
   SET name = '增值包授予', updated_at = NOW()
 WHERE point_code = 'OPS_MERCHANT__TAB_PLANS';

-- sort 取 81：紧挨「增值包授予」(80)，且落在「链条画像」(90) 之前 ——
-- 同 group 的叶子必须相邻，插远了会把「增值包」这一组劈开。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_PLAN_DEFS', 'OPS_MERCHANT', '档位定义', '增值包', '/merchants?tab=plan-defs', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 81, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_PLAN_DEFS');

-- 逐行写死，不从既有点派生：gen-test-schema.py 会**静默丢掉带 JOIN 的
-- INSERT…SELECT**（V308 上踩过一次，症状是测试库缺行、生产库有行）。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_PLAN_DEFS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_PLAN_DEFS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_MERCHANT__TAB_PLAN_DEFS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_MERCHANT__TAB_PLAN_DEFS');
