-- 会员标签（P2）：系统算的与商家打的各一类。
--
-- **关系表只存 tag_no 不存文本**：改名是商家迟早要做的事，存文本的话改一次名
-- 要 update 成千上万行，而历史统计会断（同一个标签换了个叫法，还是同一个标签）。
--
-- **不提供物理删除**：引用它的活动受众、筛选条件、发放记录都会变成悬空。
-- 只有停用（老的还在、新的打不了）与合并（并进另一个，源标签保留为 MERGED）。
CREATE TABLE IF NOT EXISTS mbr_tag
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    tag_no VARCHAR(64) NOT NULL COMMENT '不可变。改名改的是 name，关系行一行不动',
    entity_no VARCHAR(64) NOT NULL COMMENT '属主体：跨门店共享 —— 同一个人在两家店买东西，仍是同一个「爱囤货」的人',
    name VARCHAR(32) NOT NULL,
    tag_type VARCHAR(8) NOT NULL DEFAULT 'MCH' COMMENT 'SYS 系统算的（不可改名、不可合并、商家不可打）/ MCH 商家的',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED 停用 / MERGED 已并入别的标签',
    merged_into VARCHAR(64) DEFAULT NULL COMMENT 'MERGED 时指向目标 tag_no，保留不删',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_tag_no (tag_no),
    UNIQUE KEY uk_mbr_tag_name (tenant_no, entity_no, name),
    KEY idx_mbr_tag_entity (entity_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='标签字典：tag_no 不可变、name 可改。不存人数，要用时 COUNT';

CREATE TABLE IF NOT EXISTS mbr_member_tag
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL COMMENT '冗余主体号：按标签筛人时不必回表',
    member_no VARCHAR(64) NOT NULL,
    tag_no VARCHAR(64) NOT NULL COMMENT '只存号不存文本',
    tag_type VARCHAR(8) NOT NULL,
    tagged_by VARCHAR(64) DEFAULT NULL COMMENT '谁打的。SYS 为空',
    tagged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_member_tag (tenant_no, member_no, tag_no),
    KEY idx_mbr_tag_filter (entity_no, tag_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员标签关系：只存标签号，文本在字典里';

-- 合并不可逆，没有日志就回答不了商家那句「我的会员标签怎么变了」。
CREATE TABLE IF NOT EXISTS mbr_tag_merge_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    from_tag_no VARCHAR(64) NOT NULL,
    to_tag_no VARCHAR(64) NOT NULL,
    affected_count INT(11) NOT NULL DEFAULT 0 COMMENT '合并当时算好存下 —— 事后再算算不出当时的样子',
    operator_no VARCHAR(64) DEFAULT NULL,
    merged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_mbr_merge_entity (entity_no, merged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='标签合并留痕：合并不可逆';
