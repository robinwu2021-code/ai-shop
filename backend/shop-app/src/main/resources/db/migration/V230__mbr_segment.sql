-- 人群（P3）：一组筛选条件，可命名保存、反复用。
--
-- **存条件不存名单**：名单每天都在变（有人昨天刚下单就不再沉睡）。
-- 存快照的话，商家两周后照着一份过期名单发券 —— 而他不会知道那份名单已经旧了。
-- 要留痕的是「发放那一刻命中了谁」，那属于发放记录，不属于人群。
--
-- rule_json 里**只存号**（标签号/门店号），不存文本：标签改名之后条件还得成立。
CREATE TABLE IF NOT EXISTS mbr_segment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    segment_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL COMMENT '商家起的名字（「南门店沉睡老客」）',
    scope_store_no VARCHAR(64) DEFAULT NULL COMMENT '限定门店。空 = 全主体',
    rule_json TEXT NOT NULL COMMENT '筛选条件：层级/标签号/来源/末单区间/消费区间',
    last_count INT(11) NOT NULL DEFAULT 0 COMMENT '上次算出多少人。**只是展示** —— 发券与触达前会当场重算',
    counted_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_segment_no (segment_no),
    UNIQUE KEY uk_mbr_segment_name (tenant_no, entity_no, name),
    KEY idx_mbr_segment_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='人群：发券、活动受众、触达共用同一份条件';
