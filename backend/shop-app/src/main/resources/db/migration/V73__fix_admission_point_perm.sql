-- 修正「准入与保证金」功能点的权限码。
--
-- V72 里它带的是 merchant:admission:read —— 那个码**没有登记进 ops-web 的
-- UI_PERM_MAP**，于是 perm_code 落成 NULL、backend_status 落成 UNMAPPED。
--
-- 后果比"少个映射"严重：前端 can() 是**先查映射、后判通配**的
-- （见 ops-web/lib/permissions.ts 的注释：反过来会让超管看到后端根本不存在的入口），
-- 所以未登记的码一律判无权限，**超管也不例外** —— 这个叶子直接从菜单里消失，
-- 而没有任何报错，看起来就像「这个功能没做」。
--
-- 改回 merchant:merchant:read（已登记）。要用专属的 admission 码，
-- 得先在 perm-map.ts 里登记，那是另一件事。
-- nav.test.ts 已加守卫：叶子的 perm 必须在 UI_PERM_MAP 里。
--
-- ⚠️ 不去改 V72：它已被 Flyway 记录，改文件会让下次启动校验和不匹配、服务起不来。

UPDATE sys_function_point
   SET ui_perm_code  = 'merchant:merchant:read',
       perm_code     = 'merchant:merchant:read',
       backend_status = 'IMPLEMENTED'
 WHERE point_code = 'OPS_MERCHANT__TAB_ADMISSION';

-- 授权跟着补：持有 merchant:merchant:read 的内置角色都应当能看到这个叶子。
-- 用「谁已经有同码的其他功能点」来推，而不是写死角色名单 —— 写死的名单会过期。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT DISTINCT rp.role_code, 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW()
  FROM sys_role_point rp
  JOIN sys_function_point fp ON fp.point_code = rp.point_code
 WHERE rp.end_code = 'OPS'
   AND fp.perm_code = 'merchant:merchant:read'
   AND NOT EXISTS (
         SELECT 1 FROM sys_role_point x
          WHERE x.role_code = rp.role_code
            AND x.point_code = 'OPS_MERCHANT__TAB_ADMISSION'
            AND x.end_code = 'OPS');
