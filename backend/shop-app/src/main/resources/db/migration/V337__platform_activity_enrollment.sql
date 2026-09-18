-- 平台活动与报名 · 自己组合（TDD-营销域-详细设计 §1.6 · §2.6 · 开发计划 P3）。
--
-- 平台活动 = owner = PLATFORM 的活动：规则、排期、玩法与商家活动同一个模型，
-- 多出来的只有出资比例、报名截止、报名门槛与「已被通过的报名占掉的预算」。
--
-- ⚠️ **entity_no 不改成可空**：平台活动写哨兵值 'PLATFORM'。
-- 改成可空要 MODIFY COLUMN，而 H2 测试库的生成器对它是盲点；且现有每一条按 entity_no = 商家 的查询
-- 天然看不到哨兵行 —— 商家的活动列表、算价、数据域都不用改一行就不会误把平台活动当成自己的。

-- ── 1. 活动上的平台字段（商家活动全为缺省值，行为字节级不变） ──────────────────────

ALTER TABLE pmt_activity
    ADD COLUMN owner VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'MERCHANT / PLATFORM',
    ADD COLUMN platform_share_bp INT(11) DEFAULT NULL COMMENT '平台出资占优惠额的万分比：10000 全额 / 5000 一半 / 0 不出。商家活动为空',
    ADD COLUMN enroll_deadline BIGINT(20) DEFAULT NULL COMMENT '报名截止（毫秒）。平台活动才有',
    ADD COLUMN enroll_rule TEXT DEFAULT NULL COMMENT '报名门槛 JSON：minRating / noViolationDays / categoryNos / cityCodes',
    ADD COLUMN enroll_reserved_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '已通过的报名占掉的平台预算（分）。带条件 UPDATE 占，不超过 budget_minor';

-- ── 2. 报名单 ───────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS pmt_enrollment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    enrollment_no VARCHAR(64) NOT NULL,
    activity_no VARCHAR(64) NOT NULL COMMENT '平台活动',
    entity_no VARCHAR(64) NOT NULL COMMENT '报名商家。数据域锚点',
    quota INT(11) NOT NULL COMMENT '商家报的份数',
    quota_used INT(11) NOT NULL DEFAULT 0 COMMENT '已用份数。下单时带条件 UPDATE 推进',
    platform_max_minor BIGINT(20) NOT NULL COMMENT '份数 × 每单平台最多补贴，提交时算定',
    merchant_max_minor BIGINT(20) NOT NULL COMMENT '份数 × 每单商家最多承担，提交时算定',
    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED / APPROVED / REJECTED / WITHDRAWN',
    reviewed_by VARCHAR(64) DEFAULT NULL,
    reviewed_at BIGINT(20) DEFAULT NULL,
    reject_reason VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_no (enrollment_no),
    UNIQUE KEY uk_enrollment_once (tenant_no, activity_no, entity_no),
    KEY idx_enrollment_entity (entity_no, status)
) COMMENT='平台活动报名单';

CREATE TABLE IF NOT EXISTS pmt_enrollment_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    enrollment_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_goods (enrollment_no, goods_no)
) COMMENT='报名的商品';

-- ── 3. 自己组合：多条件（全部满足）× 多利益 ─────────────────────────────────────

CREATE TABLE IF NOT EXISTS pmt_activity_rule
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'CONDITION / BENEFIT',
    seq INT(11) NOT NULL COMMENT '利益按 seq 依次生效',
    rule_type VARCHAR(16) NOT NULL COMMENT '条件：AMOUNT / QTY / GOODS；利益：CUT / PERCENT / POINTS',
    params TEXT NOT NULL COMMENT 'JSON，按 rule_type 校验',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_activity_rule (activity_no, kind, seq)
) COMMENT='自己组合：多条件 × 多利益。有行时优先于主表的单条触发 × 利益';

-- ── 4. 运营端菜单：营销页的两个平台活动 tab（原型 s29 · s30） ──────────────────────
-- sort 接在敞口两条（60/70）之后，不插队。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MARKETING__TAB_PLATFORM', 'OPS_MARKETING', '平台活动', '平台活动', '/marketing?tab=platform', 'marketing:campaign:read', 'marketing:campaign:read', 'IMPLEMENTED', 1, 'P-7.2', 'MENU', 80, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MARKETING__TAB_PLATFORM');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MARKETING__TAB_PLATFORMAUDIT', 'OPS_MARKETING', '报名审核', '平台活动', '/marketing?tab=platformAudit', 'marketing:campaign:read', 'marketing:campaign:read', 'IMPLEMENTED', 1, 'P-7.2', 'MENU', 90, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MARKETING__TAB_PLATFORMAUDIT');

-- 角色授权逐行写（生成 H2 库的脚本不认多行拼接的子查询）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MARKETING__TAB_PLATFORM', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MARKETING__TAB_PLATFORM' AND x.end_code='OPS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MARKETING__TAB_PLATFORMAUDIT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MARKETING__TAB_PLATFORMAUDIT' AND x.end_code='OPS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_MARKETING__TAB_PLATFORM', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_MARKETING__TAB_PLATFORM' AND x.end_code='OPS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_MARKETING__TAB_PLATFORMAUDIT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_MARKETING__TAB_PLATFORMAUDIT' AND x.end_code='OPS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_MARKETING__TAB_PLATFORM', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_MARKETING__TAB_PLATFORM' AND x.end_code='OPS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_MARKETING__TAB_PLATFORMAUDIT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_MARKETING__TAB_PLATFORMAUDIT' AND x.end_code='OPS');
