-- 「测试号固定验证码」这一页的菜单功能点与角色授权（TDD-测试号固定验证码）。
--
-- **生成方式**：`ops-web/scripts/gen-perm-seed.mjs` 是全量重生成器（重造 V62 的整份数据），
-- 不适合直接落成增量迁移。做法与 V99 / V301 / V303 相同：跑一遍生成器，从输出里逐字取出
-- 与本次相关的那几行，其余一行不动。point_code 与 sort 都是它给的值，不是我编的。
--
-- **为什么必须有这一条迁移**：运营端菜单的真源在库里（sys_function_point），
-- 只改 nav.ts 的话，接真后端时这一项对**除超管以外的所有人**都不可见，而没有任何东西报错。
--
-- **两个码要两个功能点**：库里「角色 → 后端权限码」是顺着功能点的 perm_code 推出来的
-- （OpsPermConfigFlowTest#dbConfigMatchesHardcoded 逐条比对它与 Perms.ROLE_PERMS），
-- 菜单点挂 read（它决定看不看得见入口），写码走生成器同样会产出的 ACTION 点。
--
-- **两个点都只授 SUPER_ADMIN，一个别的角色都没有** —— 这不是漏配：
-- 白名单里每一行都是一把能登进那个手机号账号的钥匙，连「能看」都等于知道那几个登录码
-- （列表页把固定验证码明文显示出来，不显示就填不进苹果审核资料）。
-- 所以 Perms.java 里这两个码也一个角色都没配，超管靠通配到达。
-- 将来真要给技术运维，**要两步一起做**：Perms.ROLE_PERMS 加人 + 这里回填 sys_role_point。
-- 只做前一步的后果见 V326 的文件头：接口对他开放，菜单里却没有这一项。
--
-- 写成可重入形式：迁移中途失败、本地库来回切分支都会让它再跑一次。
-- 用 INSERT…SELECT…FROM DUAL…WHERE NOT EXISTS 而不是 INSERT IGNORE：
-- sys_role_point 的唯一键是 (role_code, point_code, entity_no)，而 entity_no 在这里是 NULL ——
-- NULL 让唯一键不去重，INSERT IGNORE 挡不住重复行。FROM DUAL 在 MySQL 9.7.2 上已实测可用。

-- 菜单点。function_code 取 OPS_IAM（Rail 底部那组「平台管理」），href 带 tab。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, gated_by, sort, created_at, updated_at)
SELECT 'OPS_SYSTEM__TAB_TESTPHONE', 'OPS_IAM', '测试号固定验证码', '运行配置', '/system?tab=testPhone', 'system:testphone:read', 'system:testphone:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', NULL, 100, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_SYSTEM__TAB_TESTPHONE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_SYSTEM__TAB_TESTPHONE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_SYSTEM__TAB_TESTPHONE');

-- 写码的 ACTION 点（页面内操作，没有自己的 href）
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, gated_by, sort, created_at, updated_at)
SELECT 'ACT__SYSTEM_TESTPHONE_UPDATE', 'OPS_IAM', 'system:testphone:update', '页面内操作', NULL, 'system:testphone:update', 'system:testphone:update', 'IMPLEMENTED', 1, NULL, 'ACTION', NULL, 921, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__SYSTEM_TESTPHONE_UPDATE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__SYSTEM_TESTPHONE_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__SYSTEM_TESTPHONE_UPDATE');
