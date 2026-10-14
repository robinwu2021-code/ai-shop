-- 运营端「元器件」的岗位授权（V370 只授了超管）。
--
-- 判权在 elec-svc（ElecOpsGuard），但「谁有哪个码」由主系统按角色现算、认令牌时带过去
-- （InternalElecEndpoint → ElecInternal.OPS_PERMS），所以授权照常登在这里。
-- 设计与理由见 docs/technical/TDD-元器件-运营端岗位授权.md。四处要一致：
-- 本迁移 · Perms.ROLE_PERMS · schema-test.sql 种子 · ops-web permissions.ts 的 BACKEND_ROLE_PERMS。
--
-- 一、新岗位 ELEC_ADMIN「元器件负责人」：元器件六个码全给，前期由一个人掌控元器件后台全局（2026-09-30 用户定）。
--    不用 elec:* 通配：授权按功能点逐条登记、对账测试逐码比 —— **元器件以后加新码时要给它补上**。
--    另给工作台（ACT__DASHBOARD_OVERVIEW_READ）：平台规矩是人人能看工作台，否则登录后首页是空的。
--    代价是他看得见平台经营数据（GMV 等）—— V304 说这类可见性「该由人来定」，用户 2026-09-30 定了：给。
--    除工作台外不碰电商任何一块（那是超管）。
--
-- 二、既有岗位按「最小够用」分（2026-09-30 用户确认），运营端的权限配置界面里随时能改：
--   BD         撮合报价、找货源、管理供应商    → 询报价 + 报价 · 供应商 + 管理 · 料号与库存
--   GOODS_OPS  类目与标准库的主数据本来就归它 → 基础数据（厂牌与别名）· 料号与库存
--   RISK       暂停不守信的供应商是风控动作   → 供应商 + 管理
--   SUPPORT    接买家电话时查询价进度         → 询报价（只看）
-- 其余岗位（FINANCE / CAMPAIGN_OPS / COMMUNITY_OPS / AUDITOR / ANALYST / TECH_OPS）与元器件无关，不给。
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at)
VALUES ('ELEC_ADMIN', '元器件负责人', 'OPS', 1, 0, 115, NOW(), NOW());

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('ELEC_ADMIN', 'OPS_ELEC', 'OPS', NOW(), NOW()),
    ('ELEC_ADMIN', 'ACT__ELEC_RFQ_QUOTE', 'OPS', NOW(), NOW()),
    ('ELEC_ADMIN', 'OPS_ELEC__TAB_SUPPLIER', 'OPS', NOW(), NOW()),
    ('ELEC_ADMIN', 'ACT__ELEC_SUPPLIER_MANAGE', 'OPS', NOW(), NOW()),
    ('ELEC_ADMIN', 'OPS_ELEC__TAB_PART', 'OPS', NOW(), NOW()),
    ('ELEC_ADMIN', 'OPS_ELEC__TAB_BASE', 'OPS', NOW(), NOW()),
    ('ELEC_ADMIN', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW()),
    ('BD', 'OPS_ELEC', 'OPS', NOW(), NOW()),
    ('BD', 'ACT__ELEC_RFQ_QUOTE', 'OPS', NOW(), NOW()),
    ('BD', 'OPS_ELEC__TAB_SUPPLIER', 'OPS', NOW(), NOW()),
    ('BD', 'ACT__ELEC_SUPPLIER_MANAGE', 'OPS', NOW(), NOW()),
    ('BD', 'OPS_ELEC__TAB_PART', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_ELEC__TAB_BASE', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_ELEC__TAB_PART', 'OPS', NOW(), NOW()),
    ('RISK', 'OPS_ELEC__TAB_SUPPLIER', 'OPS', NOW(), NOW()),
    ('RISK', 'ACT__ELEC_SUPPLIER_MANAGE', 'OPS', NOW(), NOW()),
    ('SUPPORT', 'OPS_ELEC', 'OPS', NOW(), NOW());
