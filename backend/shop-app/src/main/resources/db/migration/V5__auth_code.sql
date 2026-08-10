-- 类目授权码（方案见 docs/technical/类目树补齐方案.md §二）
--
-- 为什么必须有这张表：V4 给类目挂上了 required_code，但**没有任何写入路径**能给商家授权。
-- 只有门槛没有发证机关，结果是挂了门槛的类目**永远拒绝所有人** ——
-- 而商家看到的只是「你还没有资质授权」，去哪申请没人知道。
-- 一个只会拒绝的校验比没有校验更糟：它看起来在工作。
--
-- 授权按**码**而不是按类目节点：`CAT111 叶菜`、`CAT112 根茎菜` 都要 `FRESH_VEG`。
-- 类目树会重构（合并、改名、加层），而「能不能卖菜」这件事不会。
-- 按节点授权的话，运营每合并一次类目就要重新给全部商家授一遍权。
CREATE TABLE IF NOT EXISTS sys_auth_code
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL COMMENT '授权码，如 FRESH_VEG。prd_category.required_code 指向它',
    name VARCHAR(64) NOT NULL COMMENT '展示名，运营授权时看到的就是它',
    required_qualification VARCHAR(64) DEFAULT NULL COMMENT '需要的资质证件名。空 = 无证件要求',
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
    UNIQUE KEY uk_sys_auth_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='类目授权码：按码授权，不按类目节点';

-- 与 ops-web 的 mock（lib/mock/db/merchant.ts 的 authCodes）逐条对齐
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FRESH_VEG',      '蔬菜',     '食品经营许可证', 10, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('FRESH_FRUIT',    '水果',     '食品经营许可证', 20, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('FRESH_DAIRY',    '乳制品',   '食品经营许可证', 30, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('FOOD',           '熟食加工', '食品经营许可证', 40, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('DAILY',          '日用百货', NULL,             50, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SERVICE_REPAIR', '维修服务', '家电维修资质',   60, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
