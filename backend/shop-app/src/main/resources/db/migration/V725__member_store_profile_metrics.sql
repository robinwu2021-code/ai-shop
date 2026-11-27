-- Run with legacy member/profile writers paused. Archived source rows stay in V224.
-- Resolve each live row before altering the destination. Missing or ambiguous identity,
-- store ownership, or an archived destination fails the insert into this staging table.
CREATE TEMPORARY TABLE tmp_member_store_mapping (
    legacy_id BIGINT NOT NULL PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    customer_no VARCHAR(64) NOT NULL,
    profile_id BIGINT NULL,
    UNIQUE KEY uk_customer_store (tenant_no, store_no, customer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

INSERT INTO tmp_member_store_mapping (legacy_id, tenant_no, store_no, customer_no, profile_id)
SELECT s.id, s.tenant_no, st.store_no, CASE WHEN p.deleted = 1 THEN NULL ELSE u.person_no END, p.id
FROM mbr_member_store s
LEFT JOIN mbr_member m ON m.tenant_no = s.tenant_no AND m.member_no = s.member_no
    AND m.entity_no = s.entity_no AND m.deleted = 0
LEFT JOIN usr_person u ON u.tenant_no = m.tenant_no AND u.person_no = m.person_no
    AND u.status = 'ACTIVE' AND u.deleted = 0
LEFT JOIN mch_store st ON st.tenant_no = s.tenant_no AND st.store_no = s.store_no AND st.entity_no = s.entity_no
LEFT JOIN mbr_store_profile p ON p.tenant_no = s.tenant_no COLLATE utf8mb4_unicode_520_ci
    AND p.store_no = s.store_no COLLATE utf8mb4_unicode_520_ci
    AND p.customer_no = u.person_no COLLATE utf8mb4_unicode_520_ci
WHERE s.deleted = 0;

ALTER TABLE mbr_store_profile
    ADD COLUMN member_no VARCHAR(64) NULL,
    ADD COLUMN entity_no VARCHAR(64) NULL,
    ADD COLUMN legacy_member_store_id BIGINT NULL,
    ADD COLUMN first_order_at BIGINT NULL,
    ADD COLUMN last_order_at BIGINT NULL,
    ADD COLUMN order_count INT NOT NULL DEFAULT 0,
    ADD COLUMN total_spent_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN d90_order_count INT NOT NULL DEFAULT 0,
    ADD COLUMN d90_spent_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN level VARCHAR(16) NULL,
    ADD COLUMN is_first_store TINYINT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_member_store (tenant_no, member_no, store_no),
    ADD UNIQUE KEY uk_legacy_store (tenant_no, legacy_member_store_id),
    ADD KEY idx_member_store_last (entity_no, store_no, last_order_at),
    ADD KEY idx_member_store_level (entity_no, store_no, level);

UPDATE mbr_store_profile p
JOIN tmp_member_store_mapping x ON x.profile_id = p.id
JOIN mbr_member_store s ON s.id = x.legacy_id
SET p.member_no = s.member_no, p.entity_no = s.entity_no, p.legacy_member_store_id = s.id,
    p.first_order_at = s.first_order_at, p.last_order_at = s.last_order_at,
    p.order_count = s.order_count, p.total_spent_minor = s.total_spent_minor,
    p.d90_order_count = s.d90_order_count, p.d90_spent_minor = s.d90_spent_minor,
    p.level = s.level, p.is_first_store = s.is_first_store,
    p.version = p.version + 1, p.updated_at = UTC_TIMESTAMP(3), p.updated_by = 'V704';

INSERT INTO mbr_store_profile (tenant_no, store_profile_no, customer_no, store_no, member_no, entity_no,
    legacy_member_store_id, first_order_at, last_order_at, order_count, total_spent_minor,
    d90_order_count, d90_spent_minor, level, is_first_store, first_seen_at,
    created_at, created_by, updated_at, updated_by)
SELECT x.tenant_no, CONCAT('MSP-V704-', s.id), x.customer_no, x.store_no, s.member_no, s.entity_no,
    s.id, s.first_order_at, s.last_order_at, s.order_count, s.total_spent_minor,
    s.d90_order_count, s.d90_spent_minor, s.level, s.is_first_store, s.created_at,
    s.created_at, COALESCE(s.created_by, 'V704'), s.updated_at, COALESCE(s.updated_by, 'V704')
FROM tmp_member_store_mapping x JOIN mbr_member_store s ON s.id = x.legacy_id
WHERE x.profile_id IS NULL;

DROP TEMPORARY TABLE tmp_member_store_mapping;
