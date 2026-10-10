-- 权限配置落库：功能 → 功能点 → 角色 → 人员（运营端先行）。
--
-- 硬编码的问题不是「对不上」，而是**加一个角色要发一次版**。
-- 这批只把配置搬进库，**判权逻辑一行不改** —— can() 的签名与行为都不动。
-- 判权一改，所有既有测试的意义就要重新论证一遍。
--
-- 数据部分由 ops-web/scripts/gen-perm-seed.mjs 生成（真源：nav.ts × perm-map.ts × Perms.java）。

CREATE TABLE IF NOT EXISTS sys_function
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    function_code VARCHAR(64) NOT NULL COMMENT '如 OPS_MERCHANT / BIZ_ORDER',
    name VARCHAR(64) NOT NULL,
    end_code VARCHAR(8) NOT NULL COMMENT 'OPS/BIZ/MP。**进唯一键** —— 三端各有自己的 ORDER 与 FINANCE，不带端会撞码',
    icon VARCHAR(32) DEFAULT NULL COMMENT '菜单图标',
    href VARCHAR(128) DEFAULT NULL COMMENT '分区默认落地页',
    sort INT(11) NOT NULL DEFAULT 0,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_function (end_code,function_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='功能（菜单分区）';

CREATE TABLE IF NOT EXISTS sys_function_point
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    point_code VARCHAR(64) NOT NULL,
    function_code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    group_name VARCHAR(32) DEFAULT NULL COMMENT '二级分组（「入驻与资质」）。不存的话动态菜单渲染不出这一层',
    href VARCHAR(128) DEFAULT NULL,
    ui_perm_code VARCHAR(64) DEFAULT NULL COMMENT '前端 UI 码（细粒度，运营端 45 个）',
    perm_code VARCHAR(64) DEFAULT NULL COMMENT '后端权限码。**NULL = 不受权限约束（谁都能用）** —— 与 NOT_IMPLEMENTED 是两回事',
    backend_status VARCHAR(16) NOT NULL DEFAULT 'IMPLEMENTED' COMMENT 'IMPLEMENTED/NOT_IMPLEMENTED/UNMAPPED。NOT_IMPLEMENTED = 菜单灰显 + 待建角标，**不可点** —— 死按钮是「看着能点、点了出错」，禁用项从一开始就说明了自己不能用',
    ui_ready TINYINT(4) NOT NULL DEFAULT 1 COMMENT '后端通了但前端页面还没做完',
    matrix_code VARCHAR(16) DEFAULT NULL COMMENT '需求编号 P-x.y，可追溯到需求文档',
    point_type VARCHAR(8) NOT NULL DEFAULT 'MENU' COMMENT 'MENU 菜单项 / ACTION 页面内的按钮级授权。**两类都要收** —— 只收菜单的话，页面内按钮用的那些码（如 industry:manage）在库里没有落点，角色映射就与硬编码对不上',
    sort INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_point (point_code),
    KEY idx_point_function (function_code,sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='功能点（菜单叶子 / 可授权的最小动作）';

CREATE TABLE IF NOT EXISTS sys_role
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    name VARCHAR(32) NOT NULL,
    end_code VARCHAR(8) NOT NULL,
    builtin TINYINT(4) NOT NULL DEFAULT 1 COMMENT '平台预置，不可删',
    wildcard TINYINT(4) NOT NULL DEFAULT 0 COMMENT '通配角色（超管）：拥有全部权限码。**库里没有 * 这个「码」** —— 超管靠「被授予全部功能点」表达可见性，但那展开出来是一组具体码，contains("*") 永远为假。判权要的是「他有没有全部权限」这个事实本身，所以在角色上显式标出来',
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '非空 = 某商家自定义的角色',
    sort INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role (end_code,role_code,entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE IF NOT EXISTS sys_role_point
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    point_code VARCHAR(64) NOT NULL,
    end_code VARCHAR(8) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_point (role_code,point_code,entity_no),
    KEY idx_rp_point (point_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色 × 功能点。**后端未实现的功能点照样建关联** —— 补齐那天翻个状态就能用，不用重配角色';

CREATE TABLE IF NOT EXISTS sys_role_member
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    end_code VARCHAR(8) NOT NULL,
    subject_no VARCHAR(64) NOT NULL COMMENT '运营 staff_no / 商家 mch_account_no / 用户 user_no',
    role_code VARCHAR(32) NOT NULL,
    scope_no VARCHAR(64) DEFAULT NULL COMMENT '角色的作用域：B 端是 store_no，运营端为空',
    granted_by VARCHAR(64) DEFAULT NULL,
    granted_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_member (end_code,subject_no,role_code,scope_no),
    KEY idx_rm_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人员 × 角色。**唯一键含 role_code —— 这就是「一人多角色」的落点**。B 端真源仍是 mch_store_role（V18 已为多角色放宽唯一键），这里只装运营端';
-- ⚠️ 由 ops-web/scripts/gen-perm-seed.mjs 生成，**请勿手改**。
-- 真源：lib/nav.ts × lib/perm-map.ts × Perms.java —— 改了它们要重跑生成器。

INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_DASHBOARD', '经营看板', 'OPS', 'LayoutDashboard', '/', 10, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MERCHANT', '商家治理', 'OPS', 'Store', '/merchants', 20, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_01', 'OPS_MERCHANT', '入驻审核', '入驻与资质', '/merchants', 'merchant:apply:audit', 'merchant:audit', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_02', 'OPS_MERCHANT', '商家档案', '入驻与资质', '/merchants?tab=list', 'merchant:merchant:read', 'merchant:audit', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_03', 'OPS_MERCHANT', '类目授权', '入驻与资质', '/merchants?tab=categories', 'merchant:category:grant', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_04', 'OPS_MERCHANT', '认证标管理', '信用与处置', '/merchants?tab=verify', 'merchant:verify:grant', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_05', 'OPS_MERCHANT', '信用档案', '信用与处置', '/merchants?tab=credit', 'merchant:merchant:read', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_06', 'OPS_MERCHANT', '违规处置与封禁', '信用与处置', '/merchants?tab=ban', 'merchant:merchant:ban', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_STORE', '门店主页', 'OPS', 'LayoutTemplate', '/stores', 30, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_01', 'OPS_STORE', '店招公告审核', '模板与合规', '/stores', 'store:page:audit', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_02', 'OPS_STORE', '主页模板配置', '模板与合规', '/stores?tab=template', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-10.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_03', 'OPS_STORE', '店铺码生成导出', '获客', '/stores?tab=qrcode', 'store:qrcode:export', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_04', 'OPS_STORE', '获客效果看板', '获客', '/stores?tab=effect', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_PRODUCT', '商品与类目', 'OPS', 'Package', '/products', 40, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT_01', 'OPS_PRODUCT', '三级类目树', '类目', '/products', 'product:category:read', 'category:manage', 'IMPLEMENTED', 1, 'P-3.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT_02', 'OPS_PRODUCT', '商品池与审核', '商品', '/products?tab=skus', 'product:sku:read', 'goods:audit', 'IMPLEMENTED', 1, 'P-3.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT_03', 'OPS_PRODUCT', '预售额度与超卖', '库存与预售', '/products?tab=stock', 'product:stock:update', 'goods:audit', 'IMPLEMENTED', 1, 'P-3.3', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_ORDER', '交易订单', 'OPS', 'ReceiptText', '/orders', 50, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_01', 'OPS_ORDER', '订单检索', '订单', '/orders', 'order:order:read', 'order:view', 'IMPLEMENTED', 1, 'P-4.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_02', 'OPS_ORDER', '异常单处理', '订单', '/orders?tab=exception', 'order:order:modify', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_03', 'OPS_ORDER', '代客下单/取消', '订单', '/orders?tab=proxy', 'order:order:proxy', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_04', 'OPS_ORDER', '支付流水核对', '支付', '/orders?tab=pay', 'order:pay:read', 'order:view', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_05', 'OPS_ORDER', '掉单补偿', '支付', '/orders?tab=repair', 'order:pay:repair', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_06', 'OPS_ORDER', '关单策略配置', '支付', '/orders?tab=close', 'order:pay:repair', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FULFILLMENT', '履约调度', 'OPS', 'Truck', '/fulfillment', 60, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_01', 'OPS_FULFILLMENT', '到货批次与配车', '到货与分拣', '/fulfillment', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_02', 'OPS_FULFILLMENT', '按自提点汇总分拣', '到货与分拣', '/fulfillment?tab=sorting', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_03', 'OPS_FULFILLMENT', '核销监控与逾期', '核销', '/fulfillment?tab=redeem', 'fulfillment:redeem:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_04', 'OPS_FULFILLMENT', '逾期规则配置', '核销', '/fulfillment?tab=overdue', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_05', 'OPS_FULFILLMENT', '快递与轨迹', '物流', '/fulfillment?tab=express', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_06', 'OPS_FULFILLMENT', '运费模板与超区', '物流', '/fulfillment?tab=freight', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_07', 'OPS_FULFILLMENT', '第三方运力配置', '物流', '/fulfillment?tab=carrier', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.2', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_AFTERSALE', '售后治理', 'OPS', 'Undo2', '/after-sales', 70, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_01', 'OPS_AFTERSALE', '售后工单池', '处置', '/after-sales', 'aftersale:ticket:read', 'ticket:handle', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_02', 'OPS_AFTERSALE', '平台介入裁决', '处置', '/after-sales?tab=intervene', 'aftersale:ticket:handle', 'ticket:handle', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_03', 'OPS_AFTERSALE', '极速退阈值配置', '规则', '/after-sales?tab=fastrefund', 'aftersale:refund:approve', 'order:intervene', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_04', 'OPS_AFTERSALE', '退款回退分账', '规则', '/finance?tab=refund-back', 'finance:settle:execute', 'settle:manage', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MARKETING', '营销活动', 'OPS', 'Ticket', '/marketing', 80, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_01', 'OPS_MARKETING', '券模板', '优惠券', '/marketing', 'marketing:coupon:read', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_02', 'OPS_MARKETING', '发放记录', '优惠券', '/marketing?tab=issues', 'marketing:coupon:read', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_03', 'OPS_MARKETING', '活动（秒杀/满减/买赠）', '活动', '/marketing?tab=campaigns', 'marketing:campaign:update', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_04', 'OPS_MARKETING', '首页楼层与 Banner', '内容位', '/marketing?tab=slots', 'marketing:slot:update', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.3', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_05', 'OPS_MARKETING', '会员卡与权益', '会员', '/marketing?tab=member', 'marketing:member:update', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.4', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROUP', '团购与求团', 'OPS', 'Users', '/groups', 90, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP_01', 'OPS_GROUP', '商家团（审核与监控）', '商家团', '/groups', 'group:campaign:audit', 'marketing:govern', 'IMPLEMENTED', 1, 'P-8.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP_02', 'OPS_GROUP', '需求单池与指派', '求团撮合', '/groups?tab=demands', 'group:demand:read', 'quote:govern', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP_03', 'OPS_GROUP', '改价留痕与毁约', '求团撮合', '/groups?tab=quotes', 'group:demand:read', 'quote:govern', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROWTH', '增长与归因', 'OPS', 'TrendingUp', '/growth', 100, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH_01', 'OPS_GROWTH', '归因规则', '归因引擎', '/growth', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH_02', 'OPS_GROWTH', '归因链路审计', '归因引擎', '/growth?tab=traces', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH_03', 'OPS_GROWTH', '邀请有礼配置', '裂变活动', '/growth?tab=fission', 'growth:fission:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FINANCE', '结算与资金', 'OPS', 'Wallet', '/finance', 110, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_01', 'OPS_FINANCE', '结算单与分账', '分账结算', '/finance', 'finance:settle:read', 'settle:manage', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_02', 'OPS_FINANCE', '分账明细', '分账结算', '/finance?tab=splits', 'finance:settle:read', 'settle:manage', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_03', 'OPS_FINANCE', '退款回退分账', '分账结算', '/finance?tab=refund-back', 'finance:settle:execute', 'settle:manage', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_04', 'OPS_FINANCE', '分档费率与服务费', '费率', '/finance?tab=rates', 'finance:rate:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_05', 'OPS_FINANCE', '提现审批', '提现与税', '/finance?tab=withdraw', 'finance:withdraw:approve', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_06', 'OPS_FINANCE', '发票与个税', '提现与税', '/finance?tab=invoice', 'finance:invoice:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_REVIEW', '评价治理', 'OPS', 'Star', '/reviews', 120, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW_01', 'OPS_REVIEW', '评价审核', '审核', '/reviews', 'review:review:audit', 'review:govern', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW_02', 'OPS_REVIEW', '恶意差评申诉裁决', '审核', '/reviews?tab=appeals', 'review:review:audit', 'review:govern', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW_03', 'OPS_REVIEW', '评分算法参数', '评分', '/reviews?tab=score', 'review:score:update', 'review:govern', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MESSAGE', '消息与客服', 'OPS', 'MessageSquare', '/messages', 130, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE_01', 'OPS_MESSAGE', '消息模板与推送', '触达', '/messages', 'message:template:read', 'ticket:handle', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE_02', 'OPS_MESSAGE', '客服工单与代客留痕', '客服', '/messages?tab=tickets', 'message:ticket:read', 'ticket:handle', 'IMPLEMENTED', 1, 'P-14.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE_03', 'OPS_MESSAGE', '帮助中心维护', '客服', '/messages?tab=faq', 'message:faq:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-14.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_COMMUNITY', '社区与网点', 'OPS', 'MapPin', '/communities', 140, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY_01', 'OPS_COMMUNITY', '社区网格', '社区网格', '/communities', 'community:community:read', 'community:view', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY_02', 'OPS_COMMUNITY', '自提点', '自提点', '/communities?tab=pickups', 'community:pickup:read', 'community:view', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY_03', 'OPS_COMMUNITY', '临时点监控', '自提点', '/communities?tab=neighbor', 'community:pickup:read', 'community:view', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_CONTENT', '素材与内容', 'OPS', 'Images', '/contents', 150, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT_01', 'OPS_CONTENT', '素材中心与分发', '素材', '/contents', 'content:material:read', 'content:govern', 'IMPLEMENTED', 1, 'P-15.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT_02', 'OPS_CONTENT', '种草内容审核', '内容', '/contents?tab=audit', 'content:material:audit', 'content:govern', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT_03', 'OPS_CONTENT', '榜单与问答', '内容', '/contents?tab=rank', 'content:material:update', 'content:govern', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_RISK', '风控', 'OPS', 'ShieldAlert', '/risk', 160, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK_01', 'OPS_RISK', '风险事件（三类）', '识别', '/risk', 'risk:rule:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK_02', 'OPS_RISK', '黑名单与申诉', '处置', '/risk?tab=blacklist', 'risk:blacklist:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK_03', 'OPS_RISK', '拦截规则配置', '处置', '/risk?tab=rules', 'risk:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_IAM', '员工与权限', 'OPS', 'UserCog', '/iam', 170, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM_01', 'OPS_IAM', '员工账号与数据域', '账号', '/iam', 'iam:staff:read', 'staff:manage', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM_02', 'OPS_IAM', '角色与 RBAC', '账号', '/iam?tab=roles', 'iam:role:grant', 'staff:manage', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM_03', 'OPS_IAM', '操作审计日志', '审计', '/iam?tab=audit', 'iam:audit:read', 'audit:view', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_SYSTEM', '系统配置', 'OPS', 'Settings', '/system', 180, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM_01', 'OPS_SYSTEM', '外观与规则文案', '外观与语言', '/system', 'system:theme:update', 'platform:config', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM_02', 'OPS_SYSTEM', '市场/货币/汇率', '外观与语言', '/system?tab=market', 'system:param:read', 'platform:config', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM_03', 'OPS_SYSTEM', '开关与灰度', '运行配置', '/system?tab=flags', 'system:param:read', 'platform:config', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_01', 'OPS_PRODUCT', 'product:sku:audit', '页面内操作', NULL, 'product:sku:audit', 'goods:audit', 'IMPLEMENTED', 1, NULL, 'ACTION', 901, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_02', 'OPS_PRODUCT', 'product:category:update', '页面内操作', NULL, 'product:category:update', 'category:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 902, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_03', 'OPS_SYSTEM', 'category:manage', '页面内操作', NULL, 'category:manage', 'category:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 903, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_04', 'OPS_COMMUNITY', 'community:community:update', '页面内操作', NULL, 'community:community:update', 'industry:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 904, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_05', 'OPS_COMMUNITY', 'community:pickup:update', '页面内操作', NULL, 'community:pickup:update', 'industry:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 905, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_06', 'OPS_MESSAGE', 'message:ticket:handle', '页面内操作', NULL, 'message:ticket:handle', 'ticket:handle', 'IMPLEMENTED', 1, NULL, 'ACTION', 906, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_07', 'OPS_MARKETING', 'marketing:coupon:issue', '页面内操作', NULL, 'marketing:coupon:issue', 'marketing:govern', 'IMPLEMENTED', 1, NULL, 'ACTION', 907, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_08', 'OPS_GROUP', 'group:demand:assign', '页面内操作', NULL, 'group:demand:assign', 'quote:govern', 'IMPLEMENTED', 1, NULL, 'ACTION', 908, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_09', 'OPS_MESSAGE', 'message:template:update', '页面内操作', NULL, 'message:template:update', 'ticket:handle', 'IMPLEMENTED', 1, NULL, 'ACTION', 909, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_10', 'OPS_SYSTEM', 'system:env:switch', '页面内操作', NULL, 'system:env:switch', NULL, 'NOT_IMPLEMENTED', 1, NULL, 'ACTION', 910, NOW(), NOW());

INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('SUPER_ADMIN', '超级管理员', 'OPS', 1, 1, 10, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_10', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('BD', '商家运营', 'OPS', 1, 0, 20, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('GOODS_OPS', '商品运营', 'OPS', 1, 0, 30, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('SUPPORT', '客服', 'OPS', 1, 0, 40, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('CAMPAIGN_OPS', '活动运营', 'OPS', 1, 0, 50, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('COMMUNITY_OPS', '社区运营', 'OPS', 1, 0, 60, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('AUDITOR', '审核员', 'OPS', 1, 0, 70, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('FINANCE', '财务', 'OPS', 1, 0, 80, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('RISK', '风控', 'OPS', 1, 0, 90, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('ANALYST', '数据分析', 'OPS', 1, 0, 100, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('TECH_OPS', '技术运维', 'OPS', 1, 0, 110, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());

-- 人员 × 角色：把 sys_ops_staff.roles 这个 JSON 列展开成行。
-- JSON 列查不了「哪些人有 FINANCE 角色」，而那是权限审计最常问的一句。
--
-- ⚠️ 存量数据在这里展开；**新账号由 DevSeeder / setStaffRole 同步写**。
--    只靠这条迁移的话，开发库里一行都插不出来 —— 员工是应用启动后才写的，
--    迁移执行时 sys_ops_staff 还是空的。那一版的症状是「BD 登录后菜单全空」，
--    而库里的角色配置看着完全正常。
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'SUPER_ADMIN', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"SUPER_ADMIN"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'BD', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"BD"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'GOODS_OPS', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"GOODS_OPS"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'SUPPORT', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"SUPPORT"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'CAMPAIGN_OPS', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"CAMPAIGN_OPS"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'COMMUNITY_OPS', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"COMMUNITY_OPS"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'AUDITOR', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"AUDITOR"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'FINANCE', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"FINANCE"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'RISK', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"RISK"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'ANALYST', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"ANALYST"%';
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, 'TECH_OPS', UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE '%"TECH_OPS"%';
