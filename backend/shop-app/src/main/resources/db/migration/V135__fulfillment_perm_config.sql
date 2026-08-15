-- 履约调度七个菜单点接上后端权限码（P-5.1 / P-5.2 后端落地）。
--
-- V72 灌这七个点时 perm_code 全是 NULL、backend_status='NOT_IMPLEMENTED' ——
-- 那是**如实反映**：当时 Perms.java 里一个 fulfillment 码都没有。
-- 现在四个码有了、15 条端点有了，这份配置必须跟上。
--
-- **不跟上的后果是静默降级**：菜单里没有这一项，而页面、路由、后端全在。
-- 看的人只会觉得「这功能怎么没做」，而它做完了 —— 差的是数据库里的一行。
-- （V99 的抬头记着同一个坑，那次是门店档案。）
--
-- 授权只给 **COMMUNITY_OPS（社区运营）**：矩阵 §2.3 原话
-- 「社区网格、自提点建档与启停、**履约调度**」。
-- 刻意不给客服：他的数据边界是「按工单授权」，而 /ops/shipments 是全平台运单 ——
-- 要让客服查一单物流，该做的是工单里的订单维度入口，不是把全量运单表发出去。
-- SUPER_ADMIN 的七行 V72 已经有了，这里不重复插。

UPDATE sys_function_point SET perm_code = 'fulfillment:batch:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT';
UPDATE sys_function_point SET perm_code = 'fulfillment:batch:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_SORTING';
UPDATE sys_function_point SET perm_code = 'fulfillment:redeem:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_REDEEM';
UPDATE sys_function_point SET perm_code = 'fulfillment:rule:update', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_OVERDUE';
-- 快递与运费两个 tab 的 ui_ready 在 V72 里是 0（当时前端标的是「未就绪」）。
-- 页面早就写完了（express-tab / freight-tab 都在跑 mock），一起改成 1 ——
-- 留着 0 的话，权限树上这两项会显示成「界面还没做」，而运营点进去是完整的页面。
UPDATE sys_function_point SET perm_code = 'fulfillment:logistics:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_EXPRESS';
UPDATE sys_function_point SET perm_code = 'fulfillment:rule:update', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_FREIGHT';
UPDATE sys_function_point SET perm_code = 'fulfillment:logistics:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_CARRIER';

-- 可重入写法（SELECT … FROM DUAL WHERE NOT EXISTS）：裸 VALUES 撞上唯一键就是 1062，
-- 而重跑不是异常情况 —— 迁移中途失败、本地库来回切分支都会让它再跑一次。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_SORTING', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_SORTING');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_REDEEM', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_REDEEM');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_OVERDUE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_OVERDUE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_EXPRESS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_EXPRESS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_FREIGHT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_FREIGHT');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_CARRIER', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_CARRIER');
