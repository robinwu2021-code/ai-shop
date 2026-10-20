-- Source roots serialize legacy writes with offline migration drain/freeze; original business states remain intact.
ALTER TABLE mkt_coupon
    ADD COLUMN migration_state VARCHAR(24) NOT NULL DEFAULT 'OPEN' COMMENT '源迁移写栅栏状态',
    ADD COLUMN migration_batch_no VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前或已中止批次墓碑',
    ADD COLUMN migration_watermark CHAR(64) COLLATE utf8mb4_bin NULL COMMENT '受控源快照HMAC水位',
    ADD COLUMN migration_frozen_revision BIGINT NULL COMMENT '冻结时独立修订，不随决定审计变更';
ALTER TABLE pmt_coupon
    ADD COLUMN migration_state VARCHAR(24) NOT NULL DEFAULT 'OPEN' COMMENT '源迁移写栅栏状态',
    ADD COLUMN migration_batch_no VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前或已中止批次墓碑',
    ADD COLUMN migration_watermark CHAR(64) COLLATE utf8mb4_bin NULL COMMENT '受控源快照HMAC水位',
    ADD COLUMN migration_frozen_revision BIGINT NULL COMMENT '冻结时独立修订，不随决定审计变更';
ALTER TABLE mkt_coupon ADD CONSTRAINT ck_mkt_migration_state CHECK (
    migration_state IN ('OPEN','DRAINING','FROZEN','COMMIT_DECIDED','ABORT_DECIDED')
    AND (migration_state='OPEN' OR migration_batch_no IS NOT NULL)
    AND (migration_state NOT IN ('FROZEN','COMMIT_DECIDED') OR (migration_watermark IS NOT NULL AND migration_frozen_revision IS NOT NULL))
    AND (migration_frozen_revision IS NULL OR migration_frozen_revision>=0));
ALTER TABLE pmt_coupon ADD CONSTRAINT ck_pmt_migration_state CHECK (
    migration_state IN ('OPEN','DRAINING','FROZEN','COMMIT_DECIDED','ABORT_DECIDED')
    AND (migration_state='OPEN' OR migration_batch_no IS NOT NULL)
    AND (migration_state NOT IN ('FROZEN','COMMIT_DECIDED') OR (migration_watermark IS NOT NULL AND migration_frozen_revision IS NOT NULL))
    AND (migration_frozen_revision IS NULL OR migration_frozen_revision>=0));
CREATE INDEX ix_mkt_migration_batch ON mkt_coupon(tenant_no,migration_state,migration_batch_no,id);
CREATE INDEX ix_pmt_migration_batch ON pmt_coupon(tenant_no,migration_state,migration_batch_no,id);
