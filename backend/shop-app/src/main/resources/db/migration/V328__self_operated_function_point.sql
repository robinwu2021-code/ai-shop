-- 「建平台自营商家」菜单点（/merchants?tab=self-operated，矩阵 P-11.1）。
--
-- 生成方式同 V99：跑一遍 `ops-web/scripts/gen-perm-seed.mjs`，从它的全量输出里
-- **逐字取出**本次新增的那两行，其余一行不动。
--
-- 唯一改掉的是 `sort`：生成器按叶子在 nav.ts 里的位置重排，给出 80 ——
-- 而库里 80 已经是「增值包与额度」。跟着它写就是两条撞在同一个 sort 上。
-- 取 42，插在 进件看板(41) 与 无照自营风险(50) 之间，既有行一行不碰。
--
-- **只授超管，且刻意不授 BD。** perm_code `merchant:selfop:create` 不在
-- Perms.ROLE_PERMS 的任何一个角色里 —— 这不是漏配：自营主体建出来之后，
-- 平台就是那批货的销售主体、售后直接进平台仲裁，不该是招商日常能点的东西。
-- 超管是通配角色（sys_role.wildcard=1，判权时短路），但配置表仍要逐点关联：
-- 少了这一行，菜单里看不到这一项，而页面、路由、后端全在（静默降级）。
--
-- 可重入形式（WHERE NOT EXISTS）：裸 VALUES 撞唯一键是 1062，
-- 而重跑不是异常 —— 迁移中途失败、本地库来回切分支都会让它再跑一次。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_SELF_OPERATED', 'OPS_MERCHANT', '建平台自营商家', '入驻与资质', '/merchants?tab=self-operated', 'merchant:selfop:create', 'merchant:selfop:create', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 42, NOW(), NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_SELF_OPERATED');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_SELF_OPERATED', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_SELF_OPERATED');
