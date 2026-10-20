-- Core owns immutable legacy checkout intent and its terminal business decisions.
CREATE TABLE ord_legacy_coupon_checkout (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_no VARCHAR(32) NOT NULL,
 checkout_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 user_no VARCHAR(64) NOT NULL,
 entity_no VARCHAR(64) NOT NULL,
 command_hash CHAR(64) NOT NULL,
 stage_hash CHAR(64) NOT NULL,
 economic_hash CHAR(64) NOT NULL,
 fact_snapshot JSON NOT NULL,
 status VARCHAR(16) NOT NULL,
 held_snapshot JSON NULL,
 decision_snapshot JSON NULL,
 reversal_snapshot JSON NULL,
 order_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 version BIGINT NOT NULL DEFAULT 0,
 deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 updated_by VARCHAR(64) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_legacy_checkout_no (tenant_no,checkout_no),
 UNIQUE KEY uk_legacy_checkout_operation (tenant_no,operation_no),
 UNIQUE KEY uk_legacy_checkout_order (tenant_no,order_no),
 KEY ix_legacy_checkout_pending (tenant_no,status,id),
 CONSTRAINT ck_legacy_checkout_state CHECK (
   (status='OPEN' AND decision_snapshot IS NULL AND order_no IS NULL AND reversal_snapshot IS NULL)
   OR (status='ABORTED' AND decision_snapshot IS NOT NULL AND order_no IS NULL AND reversal_snapshot IS NULL)
   OR (status='COMMITTED' AND decision_snapshot IS NOT NULL AND order_no IS NOT NULL AND held_snapshot IS NOT NULL)),
 CONSTRAINT ck_legacy_checkout_not_deleted CHECK (deleted=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
