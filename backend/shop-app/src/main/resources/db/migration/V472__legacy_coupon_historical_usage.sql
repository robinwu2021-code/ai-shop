-- Original historical consumption is its own protocol, never a synthetic native use intent.
ALTER TABLE ent_usage DROP CHECK ck_usage_protocol;
ALTER TABLE ent_usage DROP INDEX uk_usage_op;
ALTER TABLE ent_usage ADD COLUMN migration_import_no VARCHAR(64) COLLATE utf8mb4_bin NULL;
ALTER TABLE ent_usage MODIFY COLUMN operator_no VARCHAR(64) NULL COMMENT '真实经办者，历史未记录则为空';
ALTER TABLE ent_usage ADD UNIQUE KEY uk_usage_protocol_op (tenant_no,protocol,operation_no);
ALTER TABLE ent_usage ADD CONSTRAINT ck_usage_protocol CHECK (
 (protocol='STANDARD' AND legacy_instance_no IS NULL AND migration_import_no IS NULL AND store_no IS NOT NULL AND store_name IS NOT NULL AND operator_no IS NOT NULL)
 OR (protocol='LEGACY' AND legacy_instance_no IS NOT NULL AND migration_import_no IS NULL AND operator_no IS NOT NULL
     AND asset_kind='ORDER_COUPON' AND currency IS NOT NULL AND currency='CNY' AND units IS NOT NULL AND units=1
     AND amount_minor IS NOT NULL AND amount_minor>0 AND source_order_no IS NOT NULL)
 OR (protocol='MIGRATED' AND migration_import_no IS NOT NULL AND asset_kind='ORDER_COUPON'
     AND currency IS NOT NULL AND currency='CNY' AND units IS NOT NULL AND units=1
     AND amount_minor IS NOT NULL AND amount_minor>=0 AND status IN ('COMMITTED','REVERSED'))
);
ALTER TABLE ent_migration_import ADD COLUMN historical_record_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ent_migration_import ADD CONSTRAINT ck_migration_history_count CHECK (historical_record_count>=0);

CREATE TABLE ent_migration_usage (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_no VARCHAR(32) NOT NULL,
 import_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 source_usage_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 source_instance_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 usage_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 instance_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 source_hash CHAR(64) NOT NULL,
 source_reverted_at DATETIME(3) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 updated_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_migration_usage_source (tenant_no,source_usage_no),
 UNIQUE KEY uk_migration_usage_target (tenant_no,usage_no),
 KEY ix_migration_usage_asset (tenant_no,instance_no,id),
 CHECK (version=0 AND deleted=0 AND CHAR_LENGTH(source_hash)=64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原PMT核销到真实历史展示记录的不可变映射';
