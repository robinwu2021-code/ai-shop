-- 提现审批（/finance?tab=withdraw）从「未实现」翻成「已实现」，并把它授给 FINANCE。
--
-- **判权读的是库**：只在 Perms.java 里加常量、只把 ops-web 的 perm-map 翻过来，
-- 对所有非超管仍然不可见 —— 而页面在、路由在、端点在，没有任何东西报错。
-- V62 建这个功能点时后端一条端点都没有，所以 perm_code 是 NULL、状态 NOT_IMPLEMENTED，
-- 且 V65 只把 01/02/03/04/06 授给了 FINANCE，**05 一直只有超管能看**。
--
-- 这一版补齐的正是 05：
--   perm_code      NULL → finance:withdraw:approve（新增码，见 Perms.FINANCE_WITHDRAW_APPROVE）
--   backend_status NOT_IMPLEMENTED → IMPLEMENTED
--   sys_role_point 补 FINANCE 一行 —— 财务是唯一该持有它的角色
--
-- ⚠️ 这个码**刻意不拆读写**（与 store:page:audit 同一个例外）：提现队列的「读」
-- 就是审批动作的一半，没有「只看提现不审提现」的岗位。
--
-- 发票与个税（OPS_FINANCE_06）不用动：V64 已把它置成 finance:invoice:read / IMPLEMENTED，
-- V65 也已授给 FINANCE —— 那时后端只有平台开票抬头两条，现在补上了列表与开票动作。
--
-- ⚠️ **点号是 OPS_FINANCE__TAB_WITHDRAW，不是 OPS_FINANCE_05**：
-- V72（perm_config_realign）把整套 OPS 功能点删了重建，序号式点号换成了 `__TAB_` 式。
-- 照着 V62/V65 的旧点号写，这两条语句会各自命中 0 行 ——
-- 而 Flyway 不报错、迁移记为成功，表现是「财务点进提现 tab 一片 403」，
-- 没有任何东西指向权限种子。**改点号的迁移会让后来引用旧点号的迁移静默失效**，
-- 加新点号之前先确认它今天还在。
--
-- 写成可重入形式：迁移中途失败、本地库来回切分支都会让它再跑一次。

UPDATE sys_function_point
   SET perm_code = 'finance:withdraw:approve', backend_status = 'IMPLEMENTED', updated_at = NOW()
 WHERE point_code = 'OPS_FINANCE__TAB_WITHDRAW';

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__TAB_WITHDRAW', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = 'FINANCE'
                      AND x.point_code = 'OPS_FINANCE__TAB_WITHDRAW');
