-- Legacy ORDER coupons only. Original asset units/sequence/ledger remain in ent_instance/ent_ledger.
CREATE TABLE ent_legacy_coupon_operation (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_no VARCHAR(32) NOT NULL,
 operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 checkout_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 origin_system VARCHAR(24) COLLATE utf8mb4_bin NOT NULL,
 origin_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 instance_no VARCHAR(64) NOT NULL,
 user_no VARCHAR(64) NOT NULL,
 entity_no VARCHAR(64) NOT NULL,
 currency VARCHAR(8) NOT NULL,
 owner_batch_no VARCHAR(64) NOT NULL,
 owner_plan_hash CHAR(64) NOT NULL,
 economic_hash CHAR(64) NOT NULL,
 command_hash CHAR(64) NOT NULL,
 source_version INT NOT NULL,
 source_snapshot JSON NOT NULL,
 context_snapshot JSON NOT NULL,
 status VARCHAR(16) NOT NULL,
 ever_held TINYINT NOT NULL DEFAULT 0,
 discount_minor BIGINT NULL,
 allocation_snapshot JSON NULL,
 rule_snapshot JSON NULL,
 hold_asset_seq BIGINT NULL,
 commit_asset_seq BIGINT NULL,
 release_asset_seq BIGINT NULL,
 reverse_asset_seq BIGINT NULL,
 commit_decision_no VARCHAR(64) NULL,
 abort_decision_no VARCHAR(64) NULL,
 reverse_decision_no VARCHAR(64) NULL,
 source_order_no VARCHAR(64) NULL,
 commit_snapshot JSON NULL,
 abort_snapshot JSON NULL,
 reverse_snapshot JSON NULL,
 held_at DATETIME(3) NULL,
 committed_at DATETIME(3) NULL,
 released_at DATETIME(3) NULL,
 reversed_at DATETIME(3) NULL,
 source_committed_at DATETIME(3) NULL,
 source_reversed_at DATETIME(3) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 updated_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_legacy_coupon_operation (tenant_no,operation_no),
 UNIQUE KEY uk_legacy_coupon_checkout (tenant_no,checkout_no),
 KEY ix_legacy_coupon_holder (tenant_no,user_no,source_committed_at,operation_no),
 KEY ix_legacy_coupon_order (tenant_no,source_order_no),
 KEY ix_legacy_coupon_recovery (tenant_no,status,id),
 CHECK (origin_system IN ('MKT_LEGACY','PMT','PAY') AND source_version=1),
 CHECK (CHAR_LENGTH(owner_plan_hash)=64 AND CHAR_LENGTH(economic_hash)=64 AND CHAR_LENGTH(command_hash)=64),
 CHECK (discount_minor IS NULL OR discount_minor>0),
 CHECK (status IN ('PREPARING','HELD','COMMITTED','RELEASED','REVERSED')),
 CHECK ((ever_held=0 AND discount_minor IS NULL AND allocation_snapshot IS NULL AND rule_snapshot IS NULL
         AND hold_asset_seq IS NULL AND held_at IS NULL AND status IN ('PREPARING','RELEASED'))
     OR (ever_held=1 AND discount_minor IS NOT NULL AND allocation_snapshot IS NOT NULL AND rule_snapshot IS NOT NULL
         AND hold_asset_seq IS NOT NULL AND hold_asset_seq>0 AND held_at IS NOT NULL AND status<>'PREPARING')),
 CHECK ((status IN ('COMMITTED','REVERSED') AND commit_asset_seq IS NOT NULL AND commit_asset_seq>hold_asset_seq AND commit_decision_no IS NOT NULL
         AND source_order_no IS NOT NULL AND commit_snapshot IS NOT NULL AND committed_at IS NOT NULL
         AND source_committed_at IS NOT NULL)
     OR (status NOT IN ('COMMITTED','REVERSED') AND commit_asset_seq IS NULL AND commit_decision_no IS NULL
         AND source_order_no IS NULL AND commit_snapshot IS NULL AND committed_at IS NULL AND source_committed_at IS NULL)),
 CHECK ((status='RELEASED' AND abort_decision_no IS NOT NULL AND abort_snapshot IS NOT NULL AND released_at IS NOT NULL
         AND ((ever_held=0 AND release_asset_seq IS NULL) OR (ever_held=1 AND release_asset_seq IS NOT NULL AND release_asset_seq>hold_asset_seq)))
     OR (status<>'RELEASED' AND abort_decision_no IS NULL AND abort_snapshot IS NULL AND released_at IS NULL
         AND release_asset_seq IS NULL)),
 CHECK ((status='REVERSED' AND reverse_asset_seq IS NOT NULL AND reverse_asset_seq>commit_asset_seq AND reverse_decision_no IS NOT NULL
         AND reverse_snapshot IS NOT NULL AND reversed_at IS NOT NULL AND source_reversed_at IS NOT NULL)
     OR (status<>'REVERSED' AND reverse_asset_seq IS NULL AND reverse_decision_no IS NULL
         AND reverse_snapshot IS NULL AND reversed_at IS NULL AND source_reversed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧ORDER券跨店准备确认与真实源回补';
