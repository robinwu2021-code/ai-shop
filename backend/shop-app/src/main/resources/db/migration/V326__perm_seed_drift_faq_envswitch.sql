-- 补两条「种子里有、实库里没有」的授权，并修一个陈掉的功能点状态。
--
-- 怎么发现的：2026-09-09 做主菜单合并时，把「合并前 11 个角色各自看得见什么」
-- 的基线与实库逐条比，发现 SUPPORT 少一条。顺着追下去发现是**两种同形状的漂移**，
-- 与菜单合并无关，是存量：
--
--   种子由 nav.ts × perm-map.ts × Perms.java 生成，改了源头它自己就跟着走；
--   而库只跟着迁移走。两者之间没有任何对账，于是源头动了、迁移没跟上时，
--   库就停在旧状态 —— 不报错，只是某个角色在菜单里少一项。
--
-- ① OPS_MESSAGE__TAB_FAQ（帮助中心维护）
--    V72 按「未实现项只授超管」的口径建的点：NOT_IMPLEMENTED、perm_code 为 NULL、
--    只授 SUPER_ADMIN。V273 后来把它翻成 IMPLEMENTED、perm_code=message:ticket:handle
--    （后端其实一直是有的，那一页在导航里灰显是误判），
--    但**没有回填其余角色的授权** —— 生成器注释里写的「其余角色等那天按真实权限码
--    重算」，那一天没有人来算。后果：客服（SUPPORT）持有 message:ticket:handle，
--    接口对他开放，菜单里却没有这一项。
--
-- ② ACT__SYSTEM_ENV_SWITCH（开关与灰度里的环境切换）
--    perm-map.ts 后来把 system:env:switch 映射到了 system:param:update，
--    而库里这一行仍是 NOT_IMPLEMENTED / perm_code=NULL —— 映射变了，没有迁移跟上。
--    后果同上：TECH_OPS 用不到它。
--
-- 全库扫过一遍：种子与库共有的 167 个功能点里，状态/权限码对不上的**只有 ① 这一个**，
-- 缺失的授权**只有这 2 条**。所以这里点修，不做系统性重写。
-- 反向的 30 条「库有种子无」是运营自建角色（ABC / REVIEW_TEST）与几个 *:read 的
-- 操作点，属正常，不动。

-- ① 陈掉的功能点状态
UPDATE sys_function_point
   SET perm_code = 'system:param:update', backend_status = 'IMPLEMENTED', updated_at = NOW()
 WHERE point_code = 'ACT__SYSTEM_ENV_SWITCH' AND perm_code IS NULL;

-- ② 两条缺失的授权。WHERE NOT EXISTS 而不是 INSERT IGNORE：
-- 这一支可能在已经补过的库上重跑，而 IGNORE 会把真正的约束错误也一起吞掉。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_FAQ', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_FAQ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'TECH_OPS', 'ACT__SYSTEM_ENV_SWITCH', 'OPS', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_point x WHERE x.role_code='TECH_OPS' AND x.point_code='ACT__SYSTEM_ENV_SWITCH');
