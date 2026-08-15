-- 增值包与额度菜单点（/merchants?tab=plans，矩阵 P-11.2）。
--
-- **生成方式**：与 V99 同法 —— `ops-web/scripts/gen-perm-seed.mjs` 是全量重生成器
-- （它重造 V62 的整份数据），不能直接落成增量迁移。跑一遍生成器，从输出里**逐字取出**
-- 本次新增的这三行（一个功能点 + 两条角色授权），其余一行不动。
--
-- 唯一的改动仍是 `sort`：生成器按叶子在 nav.ts 里的位置全表重排，本次它给出 110，
-- 而库里 OPS_MERCHANT 的最大 sort 是 70（违规处置与封禁）。跟着生成器写不会撞号，
-- 但会在 70 与 110 之间留一个没有意义的空档 —— 下次有人插叶子时，
-- 「取中间值」这个办法就会先要求他读懂这个空档是不是刻意留的。取 80 接在末尾。
--
-- 权限码复用 merchant:merchant:read（不新增码）：到期看板答的是「哪几家商家快掉下去了」，
-- 与商家档案同一批人。页内两个动作各有自己的码，**不由这个菜单点管**：
--   · 授予/延长、额度覆盖 → merchant:merchant:ban（处置面，ACT__MERCHANT_MERCHANT_BAN）
--   · 改档位定义 → system:param:update（**刻意与授予分开**：BD 能给某家授予套餐，
--     但不能改「套餐是什么」—— 后者影响这一档之后的所有订阅）
--
-- 为什么档位定义不在这里再开一个菜单点：nav.test.ts 锁着「叶子的 perm 前缀必须等于
-- 所属 section 的 module」，而它是 system:*。它作为页内区块存在，编辑按钮按 can() 显隐。
--
-- 可重入形式（SELECT … FROM DUAL WHERE NOT EXISTS）：裸 VALUES 撞唯一键就是 1062，
-- 而重跑不是异常情况。⚠️ 但幂等插入**只保证不重复建、不保证值是对的**（V151 的教训）——
-- 以后改这三行的任何一个值，都要另写一条 UPDATE，光改这里的 SELECT 是不生效的。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_PLANS', 'OPS_MERCHANT', '增值包与额度', '增值包', '/merchants?tab=plans', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 0, 'P-11.2', 'MENU', 80, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_PLANS');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_PLANS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_PLANS');

-- 商家运营（BD）：他已持有 merchant:merchant:read，且催续费本来就是他的活。
-- 漏掉这一行的表现是**静默降级** —— 菜单里没有这一项，而页面、路由、代码全在。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_MERCHANT__TAB_PLANS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_MERCHANT__TAB_PLANS');
