-- 运营端「元器件」根菜单（P-19，TDD-元器件-小程序独立工程 §2.3）。
--
-- 元器件是独立服务（elec-svc，/elec/ops/**），**判权在它那边**（ElecOpsGuard 按 ElecInternal 的六个码）；
-- 菜单与授权仍登在主系统库里 —— 运营端的菜单、权限配置界面、认令牌时带过去的码都从这里来。
-- 四个子页走同一个路由的 ?tab= 深链（静态导出没有动态路由）。
--
-- 只授超管：文档里没有为元器件指定岗位。别的角色要用，在运营端的权限配置里勾（同时改 Perms.ROLE_PERMS）。
-- sort 125：在风控（120）与权限（130）之间，不挤动已有菜单。
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
VALUES ('OPS_ELEC', '元器件', 'OPS', 'Cpu', '/elec', 125, 1, NOW(), NOW());

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('OPS_ELEC', 'OPS_ELEC', '询报价', '撮合', '/elec',
     'elec:rfq:read', 'elec:rfq:read', 'IMPLEMENTED', 1, 'P-19.1', 'MENU', 10, NOW(), NOW()),
    ('OPS_ELEC__TAB_SUPPLIER', 'OPS_ELEC', '供应商', '货源', '/elec?tab=supplier',
     'elec:supplier:read', 'elec:supplier:read', 'IMPLEMENTED', 1, 'P-19.2', 'MENU', 20, NOW(), NOW()),
    ('OPS_ELEC__TAB_PART', 'OPS_ELEC', '料号与库存', '货源', '/elec?tab=part',
     'elec:part:read', 'elec:part:read', 'IMPLEMENTED', 1, 'P-19.3', 'MENU', 30, NOW(), NOW()),
    ('OPS_ELEC__TAB_BASE', 'OPS_ELEC', '基础数据', '主数据', '/elec?tab=base',
     'elec:base:manage', 'elec:base:manage', 'IMPLEMENTED', 1, 'P-19.4', 'MENU', 40, NOW(), NOW()),
    -- 页面内的两个写操作：录入报价 / 关单 / 指派，暂停 / 恢复 / 改资料
    ('ACT__ELEC_RFQ_QUOTE', 'OPS_ELEC', 'elec:rfq:quote', '页面内操作', NULL,
     'elec:rfq:quote', 'elec:rfq:quote', 'IMPLEMENTED', 1, NULL, 'ACTION', 910, NOW(), NOW()),
    ('ACT__ELEC_SUPPLIER_MANAGE', 'OPS_ELEC', 'elec:supplier:manage', '页面内操作', NULL,
     'elec:supplier:manage', 'elec:supplier:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 911, NOW(), NOW());

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'OPS_ELEC', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_ELEC__TAB_SUPPLIER', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_ELEC__TAB_PART', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_ELEC__TAB_BASE', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'ACT__ELEC_RFQ_QUOTE', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'ACT__ELEC_SUPPLIER_MANAGE', 'OPS', NOW(), NOW());
