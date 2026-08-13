-- 把 V75 用 SELECT 推导出来的那条授权，改写成显式行。
--
-- **为什么要补这一条**：V75 的重新授权写成
--   INSERT INTO sys_role_point ... SELECT ... JOIN ...
-- 在真库上完全正确，但 H2 测试夹具（backend/scripts/gen-test-schema.py 从迁移
-- 回放出 schema-test.sql）**回放不了 SELECT 式插入** —— 而它能回放 DELETE。
-- 于是夹具停在半应用状态：V75 删掉的承载点没了，新授权没补上，
-- FINANCE 丢了 merchant:admission:read，且只在跑全量单测时才显形。
--
-- 教训写在这里：**迁移里凡是要被夹具回放的，尽量用显式 VALUES**。
-- 按 href/perm_code 推导「不写死角色名单」在真库上是对的写法，
-- 但它让这份迁移只有真库能执行 —— 而本项目的默认构建跑在 H2 上。
--
-- NOT EXISTS 保证幂等：真库上 V75 已经插过，这里是空操作。

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_ADMISSION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code='FINANCE' AND x.point_code='OPS_MERCHANT__TAB_ADMISSION');
