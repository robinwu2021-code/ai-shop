-- 经营范围拆成「履约能力 × 地理覆盖」（ADR-013 阶段二，§6.2 已拍板）。
--
-- 三档枚举同时说了两件事：COMMUNITY=靠自提点+那几个社区，CITY=上门+整座城市，
-- PLATFORM=快递+无半径。于是「我有同城配送能力，但只做这三个区」无处可填 ——
-- 选 CITY 会卖到送不到的地方，选 COMMUNITY 又被绑死在自提点上。
--
-- ⚠️ **这条迁移改的是 C 端可见性主链路**，改错的症状是「商品谁也搜不到」且不报错。
--    所以第一原则是**逐字保持今天的行为**：迁移之后每一家店可达的社区集合完全不变，
--    新能力只在商家主动去框范围之后才生效。映射表见 ADR-013 §6.2。

-- ── 1. 履约能力 ─────────────────────────────────────────────────────

ALTER TABLE mch_entity
    ADD COLUMN fulfillment_reach VARCHAR(16) NOT NULL DEFAULT 'PICKUP'
        COMMENT 'PICKUP 靠自提点 / ONSITE 上门或同城配送 / SHIPPING 快递无半径。**与地理覆盖正交** —— 这一列只说「怎么送到你手上」，能卖给谁看 mch_service_area' AFTER service_scope;

-- ── 2. 地理覆盖 ─────────────────────────────────────────────────────
--
-- **物理删除，不做逻辑删除。**
--
-- 本仓库已经在「逻辑删 + 业务唯一键」这个组合上踩过四次（门店角色、商品社区池、
-- 商家社区表各修了一个 revive）：逻辑删掉的行还占着唯一索引位，
-- 「移除之后又加回同一条」直接撞键，而商家看到的是「系统开小差了」。
--
-- 这张表是**纯关联集合**，没有任何历史价值 —— 谁在什么时候框过哪个区，
-- 该由审计日志回答，不该靠一张关联表的墓碑行。所以这里不留墓碑，
-- 从根上消掉那类 bug，而不是打第五个补丁。

CREATE TABLE IF NOT EXISTS mch_service_area
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体',
    level VARCHAR(16) NOT NULL COMMENT 'COMMUNITY 社区 / STREET 街道 / DISTRICT 区县 / CITY 城市',
    ref_code VARCHAR(64) NOT NULL COMMENT 'level=COMMUNITY 时是 cmt_community.community_no，否则是 sys_region.region_code',
    source VARCHAR(16) NOT NULL DEFAULT 'SELF' COMMENT 'SELF 商家自选 / OPS 运营指定（入驻审核时定的）',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 已生效 / PENDING 待审。勾已有社区自助生效；勾区/市要审 —— 一家菜摊声称覆盖整个西湖区，得有履约能力佐证',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0 COMMENT '恒为 0 —— 本表走物理删除，这一列只为与 BaseEntity 对齐',
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_area (entity_no,level,ref_code),
    KEY idx_service_area_ref (level,ref_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家的地理覆盖项：一行一条，可跨粒度组合';

-- ── 3. 回填：逐字保持今天的行为 ─────────────────────────────────────

UPDATE mch_entity SET fulfillment_reach = CASE service_scope
    WHEN 'CITY' THEN 'ONSITE'
    WHEN 'PLATFORM' THEN 'SHIPPING'
    ELSE 'PICKUP'
END;

-- COMMUNITY 档的社区逐条搬过来。
-- source=OPS：存量的覆盖范围是入驻审核时定的，不是商家自己勾的
INSERT INTO mch_service_area
    (entity_no, level, ref_code, source, status, tenant_no,
     created_at, created_by, updated_at, updated_by, version, deleted)
SELECT c.entity_no, 'COMMUNITY', c.community_no, 'OPS', 'ACTIVE', 'MAIN',
       NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0
FROM mch_entity_community c
JOIN mch_entity e ON e.entity_no = c.entity_no
WHERE c.deleted = 0 AND e.deleted = 0
  AND (e.service_scope IS NULL OR e.service_scope = 'COMMUNITY');

-- CITY / PLATFORM **不造覆盖项**：service_city_code 存量全是 NULL，
-- 造不出来也不该瞎造。它们靠「ONSITE/SHIPPING + 无覆盖项 = 不限」这条规则
-- 得到与今天完全相同的结果（全部开放社区）。规则见 ADR-013 §6.2。

-- ── 4. service_scope 保留一版再删 ───────────────────────────────────
--
-- 改错的症状是「商品谁也搜不到」且不报错 —— 留着它才能在出事时对照回滚。
-- 确认线上稳定之后另起一版删掉。
