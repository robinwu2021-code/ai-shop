-- 新增功能点「菜单顺序」（/iam?tab=menu）。
--
-- 调序此前寄居在角色抽屉的功能矩阵里 —— 而**调序是全局的，与角色无关**。
-- 放在那里等于暗示「这个顺序属于这个角色」，且预置角色的抽屉写着「只读」，
-- 旁边却有能点的上移下移，自相矛盾。现在单独成页。
--
-- 复用 iam:role:grant，**不新增权限码**：能配权限的人才该动菜单结构。
--
-- 两条都写成 `SELECT … FROM DUAL WHERE NOT EXISTS`：**既幂等，夹具又回放得了**。
--
-- 原先第一条是裸 VALUES（当时的理由：H2 夹具回放不了 SELECT 式插入）。
-- 但裸 VALUES 撞上 `uk_point` 就是 1062，**重跑必炸** —— 而重跑不是异常情况：
-- 迁移中途失败、库里已经有这一行、本地开发库来回切分支，都会让它再跑一次。
-- 实测就是这样：数据已在库里，V77 却停在 success=0，整个后端起不来。
--
-- 那个理由现在也不成立了：夹具生成器已经认得 `INSERT … FROM DUAL`
-- （backend/scripts/gen-test-schema.py，与 V74 同一次修的），当作可重入种子回放。
-- 幂等与可回放不再需要二选一。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_IAM__TAB_MENU', 'OPS_IAM', '菜单顺序', '账号', '/iam?tab=menu', 'iam:role:grant', 'iam:role:grant', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 30, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_IAM__TAB_MENU');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_IAM__TAB_MENU', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_IAM__TAB_MENU');
