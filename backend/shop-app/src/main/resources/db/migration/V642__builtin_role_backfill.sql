-- 补齐平台预置角色（执行方案 D1 / W1.1；TDD-B端门店功能矩阵与方案准入 §4）。
--
-- 166 的 mch_role 查不到 entity_no='*' 的预置行：V637、V640 对预置角色的补授都只改到 0 行，
-- 店长、店员登录后 perms 为空。这里按「V71 初始数据 + 之后所有补授迁移」的结果把六行补上。
--
-- 只补缺失：唯一键 uk_mch_role(entity_no, role_code) 已有的行跳过，已有行一律不动（本地、测试库照常是空操作）。
-- 只写数据，不带 DDL。权限与 BizPerms.ROLE_PERMS 由 BizRoleSeedTest 对账。

INSERT IGNORE INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('*', 'OWNER', '老板', '["*"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT IGNORE INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('*', 'MANAGER', '店长', '["biz:receive","biz:verify","biz:ship","biz:order:view","biz:stock","biz:goods","biz:campaign","biz:review","biz:aftersale","biz:customer","biz:store","biz:print:read","biz:print:submit","biz:print:reprint","biz:print:extra","biz:print:device","biz:print:config","biz:print:resolve","biz:print:simulate","biz:print:export","mch:pay:order:view","mch:pay:offline:record","mch:pay:offline:manage","biz:booking:view","biz:booking:manage","mch:pay:collect"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT IGNORE INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('*', 'CLERK', '店员', '["biz:receive","biz:verify","biz:ship","biz:order:view","biz:stock","biz:print:read","biz:print:submit","biz:print:reprint","biz:print:simulate","biz:print:export","mch:pay:order:view","mch:pay:offline:record","biz:booking:view"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT IGNORE INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('*', 'PICKER', '理货员', '["biz:receive","biz:stock"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT IGNORE INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('*', 'COURIER', '配送员', '["biz:ship","biz:order:view"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT IGNORE INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('*', 'CS', '客服', '["biz:review","biz:aftersale","biz:order:view"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
