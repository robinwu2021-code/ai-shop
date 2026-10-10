-- 权限配置对齐：point_code 改为 href 稳定派生 + 补齐 nav.ts 新增的功能点。
--
-- 方案见 docs/technical/design/TDD-权限配置对齐与tab门禁.md
--
-- 为什么要整体替换而不是挑增量：旧的 point_code 是**按顺序编号**的
-- （OPS_MERCHANT_01…），插入一个叶子会让其后全部右移。而 sys_role_point 存的正是
-- point_code —— 编号一移，原本授权「认证标管理」的角色会静默变成授权「准入与保证金」，
-- 没有报错，且是**放宽**方向。与 V62 逐行 diff 的 66 行变化里，真新增只有 6 行，
-- 其余全是这种右移。
--
-- 改成 href 派生之后这是**最后一次**全量替换：此后新增叶子只新增一行。
--
-- 三件不做的事：
--   1. 不删 sys_role        —— 里面有运营在配置页自建的角色
--   2. 不动 sys_role_member —— V62 已按 sys_ops_staff 插过，再插就是重复
--   3. 不丢任何既有授权     —— 用 href / ui_perm_code 当稳定身份把 role_point 搬过去

-- ── 1. 把**自建角色**的授权按「稳定身份」记下来（href 对菜单点，ui_perm_code 对按钮点）──
--    只记自建角色（builtin=0）：内置角色的授权是 Perms.ROLE_PERMS 的投影，
--    第 5 步会照生成器重新插一遍，两边都插就会重复。
--    而重复**不会被唯一键拦住** —— uk_role_point 含 entity_no，它是 NULL，
--    MySQL 的唯一索引不去重 NULL，INSERT IGNORE 在这里是无效的护栏。
--    所以靠「两步互不重叠」来保证，不靠索引。
CREATE TEMPORARY TABLE tmp_rp_identity AS
SELECT rp.role_code, fp.href, fp.ui_perm_code
  FROM sys_role_point rp
  JOIN sys_function_point fp ON fp.point_code = rp.point_code
  JOIN sys_role r ON r.role_code = rp.role_code AND r.end_code = rp.end_code
 WHERE rp.end_code = 'OPS' AND r.builtin = 0;

-- ── 2. 清掉旧的 OPS 配置（授权先清，避免唯一键打架）──
DELETE FROM sys_role_point     WHERE end_code = 'OPS';
DELETE FROM sys_function_point WHERE function_code LIKE 'OPS\_%';
DELETE FROM sys_function       WHERE end_code = 'OPS';

-- ── 3. 重建功能与功能点（生成器输出，point_code 已是 href 派生）──
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_DASHBOARD', '经营看板', 'OPS', 'LayoutDashboard', '/', 10, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MERCHANT', '商家治理', 'OPS', 'Store', '/merchants', 20, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_STORE', '门店主页', 'OPS', 'LayoutTemplate', '/stores', 30, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_PRODUCT', '商品与类目', 'OPS', 'Package', '/products', 40, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_ORDER', '交易订单', 'OPS', 'ReceiptText', '/orders', 50, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FULFILLMENT', '履约调度', 'OPS', 'Truck', '/fulfillment', 60, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_AFTERSALE', '售后治理', 'OPS', 'Undo2', '/after-sales', 70, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MARKETING', '营销活动', 'OPS', 'Ticket', '/marketing', 80, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROUP', '团购与求团', 'OPS', 'Users', '/groups', 90, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROWTH', '增长与归因', 'OPS', 'TrendingUp', '/growth', 100, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FINANCE', '结算与资金', 'OPS', 'Wallet', '/finance', 110, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_REVIEW', '评价治理', 'OPS', 'Star', '/reviews', 120, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MESSAGE', '消息与客服', 'OPS', 'MessageSquare', '/messages', 130, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_COMMUNITY', '社区与网点', 'OPS', 'MapPin', '/communities', 140, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_CONTENT', '素材与内容', 'OPS', 'Images', '/contents', 150, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_RISK', '风控', 'OPS', 'ShieldAlert', '/risk', 160, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_IAM', '员工与权限', 'OPS', 'UserCog', '/iam', 170, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_SYSTEM', '系统配置', 'OPS', 'Settings', '/system', 180, 1, NOW(), NOW());

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT', 'OPS_MERCHANT', '入驻审核', '入驻与资质', '/merchants', 'merchant:apply:audit', 'merchant:apply:audit', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_LIST', 'OPS_MERCHANT', '商家档案', '入驻与资质', '/merchants?tab=list', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_CATEGORIES', 'OPS_MERCHANT', '类目授权', '入驻与资质', '/merchants?tab=categories', 'merchant:category:grant', 'merchant:category:grant', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_ADMISSION', 'OPS_MERCHANT', '准入与保证金', '入驻与资质', '/merchants?tab=admission', 'merchant:admission:read', NULL, 'UNMAPPED', 0, 'P-11.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_VERIFY', 'OPS_MERCHANT', '认证标管理', '信用与处置', '/merchants?tab=verify', 'merchant:verify:grant', 'merchant:verify:grant', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_CREDIT', 'OPS_MERCHANT', '信用档案', '信用与处置', '/merchants?tab=credit', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_BAN', 'OPS_MERCHANT', '违规处置与封禁', '信用与处置', '/merchants?tab=ban', 'merchant:merchant:ban', 'merchant:merchant:ban', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE', 'OPS_STORE', '店招公告审核', '模板与合规', '/stores', 'store:page:audit', 'store:page:audit', 'IMPLEMENTED', 1, 'P-10.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE__TAB_TEMPLATE', 'OPS_STORE', '主页模板配置', '模板与合规', '/stores?tab=template', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-10.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE__TAB_QRCODE', 'OPS_STORE', '店铺码生成导出', '获客', '/stores?tab=qrcode', 'store:qrcode:export', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE__TAB_EFFECT', 'OPS_STORE', '获客效果看板', '获客', '/stores?tab=effect', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT', 'OPS_PRODUCT', '三级类目树', '类目', '/products', 'product:category:read', 'product:category:read', 'IMPLEMENTED', 1, 'P-3.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_SKUS', 'OPS_PRODUCT', '商品池与审核', '商品', '/products?tab=skus', 'product:sku:read', 'product:sku:read', 'IMPLEMENTED', 1, 'P-3.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_AUDIT', 'OPS_PRODUCT', '商品审核队列', '商品', '/products?tab=audit', 'product:sku:audit', 'product:sku:audit', 'IMPLEMENTED', 1, 'P-3.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_STOCK', 'OPS_PRODUCT', '预售额度与超卖', '库存与预售', '/products?tab=stock', 'product:stock:update', 'product:sku:audit', 'IMPLEMENTED', 1, 'P-3.3', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER', 'OPS_ORDER', '订单检索', '订单', '/orders', 'order:order:read', 'order:order:read', 'IMPLEMENTED', 1, 'P-4.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_EXCEPTION', 'OPS_ORDER', '异常单处理', '订单', '/orders?tab=exception', 'order:order:modify', 'order:order:modify', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_PROXY', 'OPS_ORDER', '代客下单/取消', '订单', '/orders?tab=proxy', 'order:order:proxy', 'order:order:proxy', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_PAY', 'OPS_ORDER', '支付流水核对', '支付', '/orders?tab=pay', 'order:pay:read', 'order:order:read', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_REPAIR', 'OPS_ORDER', '掉单补偿', '支付', '/orders?tab=repair', 'order:pay:repair', 'order:order:modify', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_CLOSE', 'OPS_ORDER', '关单策略配置', '支付', '/orders?tab=close', 'order:pay:repair', 'order:order:modify', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT', 'OPS_FULFILLMENT', '到货批次与配车', '到货与分拣', '/fulfillment', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_SORTING', 'OPS_FULFILLMENT', '按自提点汇总分拣', '到货与分拣', '/fulfillment?tab=sorting', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_REDEEM', 'OPS_FULFILLMENT', '核销监控与逾期', '核销', '/fulfillment?tab=redeem', 'fulfillment:redeem:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_OVERDUE', 'OPS_FULFILLMENT', '逾期规则配置', '核销', '/fulfillment?tab=overdue', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_EXPRESS', 'OPS_FULFILLMENT', '快递与轨迹', '物流', '/fulfillment?tab=express', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_FREIGHT', 'OPS_FULFILLMENT', '运费模板与超区', '物流', '/fulfillment?tab=freight', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_CARRIER', 'OPS_FULFILLMENT', '第三方运力配置', '物流', '/fulfillment?tab=carrier', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.2', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE', 'OPS_AFTERSALE', '售后工单池', '处置', '/after-sales', 'aftersale:ticket:read', 'aftersale:ticket:read', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE__TAB_INTERVENE', 'OPS_AFTERSALE', '平台介入裁决', '处置', '/after-sales?tab=intervene', 'aftersale:ticket:handle', 'aftersale:ticket:handle', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE__TAB_FASTREFUND', 'OPS_AFTERSALE', '极速退阈值配置', '规则', '/after-sales?tab=fastrefund', 'aftersale:refund:approve', 'aftersale:refund:approve', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE__TAB_REFUND_BACK', 'OPS_AFTERSALE', '退款回退分账', '规则', '/finance?tab=refund-back', 'finance:settle:execute', 'finance:settle:execute', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING', 'OPS_MARKETING', '券模板', '优惠券', '/marketing', 'marketing:coupon:read', 'marketing:coupon:read', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_ISSUES', 'OPS_MARKETING', '发放记录', '优惠券', '/marketing?tab=issues', 'marketing:coupon:read', 'marketing:coupon:read', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_CAMPAIGNS', 'OPS_MARKETING', '活动', '活动', '/marketing?tab=campaigns', 'marketing:campaign:update', 'marketing:campaign:update', 'IMPLEMENTED', 1, 'P-7.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_SLOTS', 'OPS_MARKETING', '首页楼层与 Banner', '内容位', '/marketing?tab=slots', 'marketing:slot:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-7.3', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_MEMBER', 'OPS_MARKETING', '会员卡与权益', '会员', '/marketing?tab=member', 'marketing:member:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-7.4', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP', 'OPS_GROUP', '商家团', '商家团', '/groups', 'group:campaign:audit', 'group:campaign:audit', 'IMPLEMENTED', 1, 'P-8.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP__TAB_DEMANDS', 'OPS_GROUP', '需求单池与指派', '求团撮合', '/groups?tab=demands', 'group:demand:read', 'group:demand:read', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP__TAB_QUOTES', 'OPS_GROUP', '改价留痕与毁约', '求团撮合', '/groups?tab=quotes', 'group:demand:read', 'group:demand:read', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH', 'OPS_GROWTH', '归因规则', '归因引擎', '/growth', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH__TAB_TRACES', 'OPS_GROWTH', '归因链路审计', '归因引擎', '/growth?tab=traces', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH__TAB_FISSION', 'OPS_GROWTH', '邀请有礼配置', '裂变活动', '/growth?tab=fission', 'growth:fission:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE', 'OPS_FINANCE', '结算单与分账', '分账结算', '/finance', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_SPLITS', 'OPS_FINANCE', '分账明细', '分账结算', '/finance?tab=splits', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_REFUND_BACK', 'OPS_FINANCE', '退款回退分账', '分账结算', '/finance?tab=refund-back', 'finance:settle:execute', 'finance:settle:execute', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_RATES', 'OPS_FINANCE', '分档费率与服务费', '费率', '/finance?tab=rates', 'finance:rate:update', 'finance:rate:update', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_WITHDRAW', 'OPS_FINANCE', '提现审批', '提现与税', '/finance?tab=withdraw', 'finance:withdraw:approve', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_INVOICE', 'OPS_FINANCE', '发票与个税', '提现与税', '/finance?tab=invoice', 'finance:invoice:read', 'finance:invoice:read', 'IMPLEMENTED', 1, 'P-12.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW', 'OPS_REVIEW', '评价审核', '审核', '/reviews', 'review:review:audit', 'review:review:audit', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW__TAB_APPEALS', 'OPS_REVIEW', '恶意差评申诉裁决', '审核', '/reviews?tab=appeals', 'review:review:audit', 'review:review:audit', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW__TAB_SCORE', 'OPS_REVIEW', '评分算法参数', '评分', '/reviews?tab=score', 'review:score:update', 'review:score:update', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE', 'OPS_MESSAGE', '消息模板与推送', '触达', '/messages', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE__TAB_TICKETS', 'OPS_MESSAGE', '客服工单与代客留痕', '客服', '/messages?tab=tickets', 'message:ticket:read', 'message:ticket:read', 'IMPLEMENTED', 1, 'P-14.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE__TAB_FAQ', 'OPS_MESSAGE', '帮助中心维护', '客服', '/messages?tab=faq', 'message:faq:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-14.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY', 'OPS_COMMUNITY', '社区网格', '社区网格', '/communities', 'community:community:read', 'community:community:read', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY__TAB_APPLIES', 'OPS_COMMUNITY', '商家提报', '社区网格', '/communities?tab=applies', 'community:community:read', 'community:community:read', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY__TAB_PICKUPS', 'OPS_COMMUNITY', '自提点', '自提点', '/communities?tab=pickups', 'community:pickup:read', 'community:pickup:read', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY__TAB_NEIGHBOR', 'OPS_COMMUNITY', '临时点监控', '自提点', '/communities?tab=neighbor', 'community:pickup:read', 'community:pickup:read', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT', 'OPS_CONTENT', '素材中心与分发', '素材', '/contents', 'content:material:read', 'content:material:read', 'IMPLEMENTED', 1, 'P-15.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT__TAB_AUDIT', 'OPS_CONTENT', '种草内容审核', '内容', '/contents?tab=audit', 'content:material:audit', 'content:material:audit', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT__TAB_RANK', 'OPS_CONTENT', '榜单与问答', '内容', '/contents?tab=rank', 'content:material:update', 'content:material:update', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK', 'OPS_RISK', '风险事件', '识别', '/risk', 'risk:rule:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK__TAB_BLACKLIST', 'OPS_RISK', '黑名单与申诉', '处置', '/risk?tab=blacklist', 'risk:blacklist:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK__TAB_RULES', 'OPS_RISK', '拦截规则配置', '处置', '/risk?tab=rules', 'risk:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM', 'OPS_IAM', '员工账号与数据域', '账号', '/iam', 'iam:staff:read', 'iam:staff:read', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM__TAB_ROLES', 'OPS_IAM', '角色与权限', '账号', '/iam?tab=roles', 'iam:role:grant', 'iam:role:grant', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM__TAB_AUDIT', 'OPS_IAM', '操作审计日志', '审计', '/iam?tab=audit', 'iam:audit:read', 'iam:audit:read', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM', 'OPS_SYSTEM', '外观与规则文案', '外观与语言', '/system', 'system:theme:update', 'system:theme:update', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_MARKET', 'OPS_SYSTEM', '市场/货币/汇率', '外观与语言', '/system?tab=market', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_FLAGS', 'OPS_SYSTEM', '开关与灰度', '运行配置', '/system?tab=flags', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_INDUSTRY', 'OPS_SYSTEM', '行业与小微白名单', '经营范围', '/system?tab=industry', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_AUTHCODE', 'OPS_SYSTEM', '经营授权码', '经营范围', '/system?tab=authCode', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_SCOPE', 'OPS_SYSTEM', '经营范围开关', '经营范围', '/system?tab=scope', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__PRODUCT_CATEGORY_UPDATE', 'OPS_PRODUCT', 'product:category:update', '页面内操作', NULL, 'product:category:update', 'product:category:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 901, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__CATEGORY_MANAGE', 'OPS_SYSTEM', 'category:manage', '页面内操作', NULL, 'category:manage', 'product:category:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 902, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__COMMUNITY_COMMUNITY_UPDATE', 'OPS_COMMUNITY', 'community:community:update', '页面内操作', NULL, 'community:community:update', 'community:community:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 903, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__COMMUNITY_PICKUP_UPDATE', 'OPS_COMMUNITY', 'community:pickup:update', '页面内操作', NULL, 'community:pickup:update', 'community:pickup:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 904, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MESSAGE_TICKET_HANDLE', 'OPS_MESSAGE', 'message:ticket:handle', '页面内操作', NULL, 'message:ticket:handle', 'message:ticket:handle', 'IMPLEMENTED', 1, NULL, 'ACTION', 905, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MARKETING_COUPON_ISSUE', 'OPS_MARKETING', 'marketing:coupon:issue', '页面内操作', NULL, 'marketing:coupon:issue', 'marketing:coupon:issue', 'IMPLEMENTED', 1, NULL, 'ACTION', 906, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__GROUP_DEMAND_ASSIGN', 'OPS_GROUP', 'group:demand:assign', '页面内操作', NULL, 'group:demand:assign', 'group:demand:assign', 'IMPLEMENTED', 1, NULL, 'ACTION', 907, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MESSAGE_TEMPLATE_UPDATE', 'OPS_MESSAGE', 'message:template:update', '页面内操作', NULL, 'message:template:update', 'message:template:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 908, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__SYSTEM_ENV_SWITCH', 'OPS_SYSTEM', 'system:env:switch', '页面内操作', NULL, 'system:env:switch', NULL, 'NOT_IMPLEMENTED', 1, NULL, 'ACTION', 909, NOW(), NOW());

-- ── 4. 把第 1 步记下的**自建角色**授权搬回来 ──
--    <=> 是 null-safe 等号：按钮点的 href 为 NULL，用普通 = 会一条都匹配不上，
--    表现是「自建角色的按钮级授权全没了」，而这一步不会报错。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT DISTINCT t.role_code, fp.point_code, 'OPS', NOW(), NOW()
  FROM tmp_rp_identity t
  JOIN sys_function_point fp
    ON fp.href <=> t.href AND fp.ui_perm_code <=> t.ui_perm_code;

-- ── 5. 内置角色的授权照生成器重建（Perms.ROLE_PERMS 的投影，权威）──
--    与第 4 步互不重叠，所以这里**不需要** IGNORE 去重；留着 IGNORE 只是
--    防止同一份迁移被重复执行时报错。
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_LIST', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_CATEGORIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_VERIFY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_CREDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_BAN', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE__TAB_TEMPLATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE__TAB_QRCODE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE__TAB_EFFECT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_SKUS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_STOCK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_EXCEPTION', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_PROXY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_REPAIR', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_CLOSE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_SORTING', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_REDEEM', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_OVERDUE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_EXPRESS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_FREIGHT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_CARRIER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE__TAB_INTERVENE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE__TAB_FASTREFUND', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_ISSUES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_CAMPAIGNS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_SLOTS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_MEMBER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP__TAB_DEMANDS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP__TAB_QUOTES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH__TAB_FISSION', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_SPLITS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_RATES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_WITHDRAW', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_INVOICE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW__TAB_APPEALS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW__TAB_SCORE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE__TAB_TICKETS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE__TAB_FAQ', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY__TAB_PICKUPS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY__TAB_NEIGHBOR', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT__TAB_RANK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK__TAB_BLACKLIST', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK__TAB_RULES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM__TAB_ROLES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_MARKET', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_FLAGS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_INDUSTRY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_AUTHCODE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_SCOPE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__PRODUCT_CATEGORY_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__CATEGORY_MANAGE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__COMMUNITY_COMMUNITY_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__COMMUNITY_PICKUP_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__MESSAGE_TICKET_HANDLE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__MARKETING_COUPON_ISSUE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__GROUP_DEMAND_ASSIGN', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__MESSAGE_TEMPLATE_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__SYSTEM_ENV_SWITCH', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_LIST', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_CATEGORIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_VERIFY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_CREDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_BAN', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_STORE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP__TAB_DEMANDS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP__TAB_QUOTES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT__GROUP_DEMAND_ASSIGN', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_SKUS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_STOCK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING__TAB_ISSUES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING__TAB_CAMPAIGNS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__PRODUCT_CATEGORY_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__CATEGORY_MANAGE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__MARKETING_COUPON_ISSUE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_EXCEPTION', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_PROXY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_REPAIR', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_CLOSE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE__TAB_INTERVENE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE__TAB_FASTREFUND', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW__TAB_APPEALS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW__TAB_SCORE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE__TAB_TICKETS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT__MESSAGE_TICKET_HANDLE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT__MESSAGE_TEMPLATE_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING__TAB_ISSUES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING__TAB_CAMPAIGNS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT__TAB_RANK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT__MARKETING_COUPON_ISSUE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_PICKUPS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_NEIGHBOR', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT__COMMUNITY_COMMUNITY_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT__COMMUNITY_PICKUP_UPDATE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT__TAB_SKUS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT__TAB_STOCK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW__TAB_APPEALS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW__TAB_SCORE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT__TAB_RANK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE__TAB_FASTREFUND', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_SPLITS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_RATES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_INVOICE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_MARKET', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_FLAGS', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_INDUSTRY', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_AUTHCODE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_SCOPE', 'OPS', NOW(), NOW());

DROP TEMPORARY TABLE tmp_rp_identity;
