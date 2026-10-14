-- 渠道报文菜单点（/finance?tab=channel-messages，矩阵 P-12.1）。
--
-- **生成方式**：`ops-web/scripts/gen-perm-seed.mjs` 是全量重生成器（重造 V62 的整份数据），
-- 不适合直接落成增量迁移。做法与 V99 相同：跑一遍生成器，从输出里**逐字取出**
-- 本次新增的那三行，其余一行不动。point_code / ui_perm_code / perm_code / matrix_code
-- 与生成器输出一致，下次全量重生成时不会漂移。
--
-- sort 取生成器给的 150（它排在 提现审批/发票与个税 之后），
-- 既有行一行不碰 —— 插在中间会让其后每一行都要跟着改。
--
-- 权限码复用 finance:recon:read（不新增码）：查报文与查对账差异是同一件事的两面，
-- 都是「账对不上时去找原因」。而新开一个码要在五处登记，多一个码就多一处会被落下。
--
-- ⚠️ 顺带补了 ops-web/lib/perm-map.ts 里 finance:recon:read 的**恒等映射**。
-- 不补的话生成器把它算成 UNMAPPED，这一行的 perm_code 会是 NULL，
-- 而角色授权是按 perm_code 推的 —— 表现是**只有超管看得见这一项**，
-- 财务角色明明持有这个码却进不去，且界面上看不出任何异常。
--
-- 写成可重入形式：裸 VALUES 撞唯一键就是 1062，而重跑不是异常情况
-- （迁移中途失败、本地库来回切分支都会让它再跑一次）。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_FINANCE__TAB_CHANNEL_MESSAGES', 'OPS_FINANCE', '渠道报文', '分账结算', '/finance?tab=channel-messages', 'finance:recon:read', 'finance:recon:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 150, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_FINANCE__TAB_CHANNEL_MESSAGES');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_FINANCE__TAB_CHANNEL_MESSAGES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_FINANCE__TAB_CHANNEL_MESSAGES');

-- 财务：他已经持有 finance:recon:read（对账差异那两页靠它）。
-- 漏掉这一行的表现是**静默降级** —— 菜单里没有这一项，而页面、路由、代码全在。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__TAB_CHANNEL_MESSAGES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='OPS_FINANCE__TAB_CHANNEL_MESSAGES');
