-- 商家提报新社区（ADR-013 阶段三）。
--
-- 商家开在一个平台还没开的小区里，今天他**无路可走**：覆盖项只能从已有社区里勾，
-- 而「让平台加一个小区」没有任何入口 —— 只能找 BD 口头说，说完没人知道进展。
--
-- 为什么是独立的提报单，而不是直接在 cmt_community 里建一行 status='PENDING'：
--   1. 待审的社区一旦进了主表，**每一个读社区的地方都要记得过滤它** ——
--      C 端选点、B 端勾选、按区展开、自提点归属……漏一处，一个还没批的小区
--      就出现在用户的选点列表里，而点进去什么都没有；
--   2. 驳回之后主表里躺着一行没人认领的垃圾，还占着社区号；
--   3. 提报是「谁在什么时候说了什么」，社区是「平台开了哪些点」—— 两件事。
--
-- 通过时才建社区行，提报单回填 community_no 指过去。

CREATE TABLE IF NOT EXISTS cmt_community_apply
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    apply_no VARCHAR(64) NOT NULL COMMENT '提报单业务键',
    entity_no VARCHAR(64) NOT NULL COMMENT '提报的商家。驳回理由要回给他，通过了也要让他知道',
    name VARCHAR(128) NOT NULL COMMENT '小区名，商家填',
    address VARCHAR(255) DEFAULT NULL COMMENT '地址，商家填。运营靠它判断是不是已有社区的另一个叫法',
    region_code VARCHAR(32) DEFAULT NULL COMMENT '所属区划（建议街道级）。**商家选的只是建议** —— 最终以运营裁决时填的为准',
    note VARCHAR(255) DEFAULT NULL COMMENT '商家的补充说明：为什么要开这个点',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审 / APPROVED 已建社区 / REJECTED 驳回',
    community_no VARCHAR(64) DEFAULT NULL COMMENT '通过后建出来的社区号。回填而不是提前占号：驳回的提报不该消耗社区号',
    reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因。**原样出现在商家 B 端**，所以驳回必须填',
    submitted_at BIGINT(20) DEFAULT NULL,
    decided_at BIGINT(20) DEFAULT NULL,
    decided_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_apply_no (apply_no),
    KEY idx_community_apply_status (status),
    KEY idx_community_apply_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家提报的新社区，审过才进 cmt_community';
