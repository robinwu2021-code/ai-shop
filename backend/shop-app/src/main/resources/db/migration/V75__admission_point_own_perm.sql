-- 「准入与保证金」改用专属权限码 merchant:admission:read。
--
-- 此前它挂 merchant:merchant:read，而 Perms.ROLE_PERMS 把 admission 两个码给的是
-- **财务**。用商家读权限的结果正好反过来：财务看不到自己有权做的事，
-- 商家运营却看得到 —— 而两边都不报错。
--
-- 根因是 ops-web 的 UI_PERM_MAP 漏登记了这两个码，can() 对未登记码一律判无权限
-- （连超管也是）。映射表已补，nav.ts 的叶子改指专属码，这里把库对齐。
--
-- V74 为这两个码建的「仅后端」占位点，read 那个已被本菜单点取代，删掉；
-- update 那个由 UI_PERM_MAP 派生成 ACTION 点，point_code 不变，无需处理。

-- ── 1. 菜单点改指专属码 ──
UPDATE sys_function_point
   SET ui_perm_code = 'merchant:admission:read',
       perm_code    = 'merchant:admission:read'
 WHERE point_code = 'OPS_MERCHANT__TAB_ADMISSION';

-- ── 2. 授权按新码重算：先清这个点的旧授权（它们是按 merchant:merchant:read 发的）──
DELETE FROM sys_role_point WHERE point_code = 'OPS_MERCHANT__TAB_ADMISSION';

-- ── 3. 持有 merchant:admission:read 的角色重新授权 ──
--    从「谁已经有同码的其他点」推，不写死角色名单 —— 写死的名单会过期
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT DISTINCT rp.role_code, 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW()
  FROM sys_role_point rp
  JOIN sys_function_point fp ON fp.point_code = rp.point_code
 WHERE rp.end_code = 'OPS'
   AND fp.perm_code = 'merchant:admission:read'
   AND fp.point_code <> 'OPS_MERCHANT__TAB_ADMISSION';

-- ── 4. V74 建的 read 占位点已被菜单点取代 ──
DELETE FROM sys_role_point   WHERE point_code = 'ACT__MERCHANT_ADMISSION_READ';
DELETE FROM sys_function_point WHERE point_code = 'ACT__MERCHANT_ADMISSION_READ';
