-- 「代商家进件」菜单点（/merchants?tab=on-behalf，矩阵 P-11.1）。
--
-- 生成方式同 V328 / V99：跑一遍 `ops-web/scripts/gen-perm-seed.mjs`（**不带
-- --emit-point-codes**，那个开关已经会重写 33 行既有码），从全量输出里逐字取出
-- 本次新增的那两行，其余一行不动。
--
-- sort 取 43：插在 建平台自营商家(42) 与 无照自营风险(50) 之间，既有行一行不碰。
-- 生成器会按 nav.ts 里的位置重排给出别的数，跟着它写就是撞号。
--
-- **授给超管与 BD，与「建平台自营商家」刻意不同。** 那一个只给超管：
-- 自营主体建出来之后平台就是那批货的销售主体，不是招商日常能点的东西。
-- 这一个是招商日常本身 —— BD 站在店里替老板录资料，不给他等于这一期没人用得了。
--
-- ⚠️ **BD 同时持有 merchant:apply:audit** —— 也就是同一个人既能制单又能审核，
-- 而制单与审核分离正是这一期的论证。分离因此钉在数据上而不是这张表上：
-- OpsServiceImpl.auditApply 拦「submitted_by == 当前审核人」。
-- 想靠不授码来实现分离是行不通的：那样代填这件事根本没人做得了。
--
-- 可重入形式（WHERE NOT EXISTS）：重跑不是异常 —— 迁移中途失败、
-- 本地库来回切分支都会让它再跑一次，而裸 VALUES 撞唯一键是 1062。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_ON_BEHALF', 'OPS_MERCHANT', '代商家进件', '入驻与资质', '/merchants?tab=on-behalf', 'merchant:apply:onbehalf', 'merchant:apply:onbehalf', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 43, NOW(), NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_ON_BEHALF');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_ON_BEHALF', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_ON_BEHALF');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_MERCHANT__TAB_ON_BEHALF', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_MERCHANT__TAB_ON_BEHALF');
