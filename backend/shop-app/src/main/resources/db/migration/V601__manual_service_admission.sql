ALTER TABLE ord_checkout_operation
    ADD COLUMN admission_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD COLUMN manual_observation_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN manual_observation_hash CHAR(64) NULL,
    ADD CONSTRAINT ck_checkout_admission_kind CHECK (admission_kind IN ('LEGACY_FUNDED','MANUAL_OFFLINE')),
    ADD CONSTRAINT ck_checkout_manual_observation CHECK (manual_observation_revision>=0 AND
        ((manual_observation_revision=0 AND manual_observation_hash IS NULL) OR
         (manual_observation_revision>0 AND manual_observation_hash IS NOT NULL)));

ALTER TABLE ord_order
    ADD COLUMN admission_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD COLUMN receipt_provenance VARCHAR(24) NULL,
    ADD CONSTRAINT ck_order_admission_kind CHECK (admission_kind IN ('LEGACY_FUNDED','MANUAL_OFFLINE')),
    ADD CONSTRAINT ck_order_manual_provenance CHECK
        (receipt_provenance IS NULL OR
         (admission_kind='MANUAL_OFFLINE' AND receipt_provenance='MANUAL_DECLARATION'
          AND pay_channel IS NULL AND pay_trade_no IS NULL));

ALTER TABLE ord_sub_order
    ADD COLUMN admission_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD CONSTRAINT ck_sub_manual_kind CHECK (admission_kind='LEGACY_FUNDED' OR
        (admission_kind='MANUAL_OFFLINE' AND fulfillment_authority IS NOT NULL
         AND fulfillment_authority='SERVICE_EXECUTION'
         AND source_operation_no IS NOT NULL AND fulfillment='STORE_VERIFY'));

ALTER TABLE prd_stock_lock
    ADD COLUMN source_manifest_hash CHAR(64) NULL,
    ADD COLUMN source_store_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN lifecycle_version INT NULL,
    ADD COLUMN confirmation_operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_stock_original_manifest CHECK
        ((source_manifest_hash IS NULL AND source_store_no IS NULL AND lifecycle_version IS NULL
          AND confirmation_operation_no IS NULL) OR
         (source_manifest_hash IS NOT NULL AND lifecycle_version IN (1,2) AND qty>0));

CREATE TABLE ord_manual_service_admission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    declaration_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    caller VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    sub_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    buyer_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    admission_kind VARCHAR(24) NOT NULL DEFAULT 'MANUAL_OFFLINE',
    actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    actor_realm VARCHAR(16) NOT NULL,
    actor_kind VARCHAR(8) NOT NULL,
    account_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_key_hash CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    method VARCHAR(24) NOT NULL,
    declared_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    note VARCHAR(500) NULL,
    receipt_provenance VARCHAR(24) NOT NULL DEFAULT 'MANUAL_DECLARATION',
    phase VARCHAR(24) NOT NULL,
    admission_revision BIGINT NOT NULL DEFAULT 1,
    order_revision BIGINT NOT NULL,
    quote_hash CHAR(64) NOT NULL,
    stock_authority VARCHAR(16) NOT NULL,
    stock_lifecycle_version INT NOT NULL,
    stock_manifest_json LONGTEXT NOT NULL,
    stock_manifest_hash CHAR(64) NOT NULL,
    stock_operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    stock_outcome VARCHAR(16) NULL,
    stock_state VARCHAR(24) NULL,
    stock_observation_hash CHAR(64) NULL,
    reason_code VARCHAR(64) NULL,
    next_retry_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_declaration (tenant_no,declaration_no),
    UNIQUE KEY uk_manual_order (tenant_no,order_no),
    UNIQUE KEY uk_manual_operation (tenant_no,caller,operation_no),
    UNIQUE KEY uk_manual_receipt_key (tenant_no,entity_no,store_no,request_key_hash),
    KEY idx_manual_stock_recovery (phase,next_retry_at,id),
    CONSTRAINT ck_manual_scope CHECK (tenant_no='MAIN' AND caller='BEAUTY' AND
        admission_kind='MANUAL_OFFLINE' AND actor_realm='MERCHANT' AND actor_kind IN ('MCH','USR')),
    CONSTRAINT ck_manual_money CHECK (declared_minor>0 AND currency='CNY' AND
        method IN ('CASH','OFFLINE_TRANSFER') AND receipt_provenance='MANUAL_DECLARATION'),
    CONSTRAINT ck_manual_phase CHECK (phase IN ('RECEIVED','STOCK_PENDING','ADMITTED','SUSPENDED','CANCELLED')),
    CONSTRAINT ck_manual_revisions CHECK (admission_revision>0 AND order_revision>=0 AND version>0 AND deleted=0),
    CONSTRAINT ck_manual_stock CHECK (stock_authority IN ('PLATFORM','DUAL','INVENTORY') AND
        stock_lifecycle_version IN (1,2) AND JSON_VALID(stock_manifest_json)
        AND JSON_TYPE(stock_manifest_json)='OBJECT'),
    CONSTRAINT ck_manual_admitted CHECK (phase<>'ADMITTED' OR
        (stock_outcome IS NOT NULL AND stock_outcome='APPLIED' AND stock_state IS NOT NULL
         AND stock_state IN ('ALLOCATED','CONSUMED') AND stock_observation_hash IS NOT NULL)),
    CONSTRAINT ck_manual_times CHECK (received_at<=recorded_at)
) ENGINE=InnoDB COMMENT='One merchant receipt declaration on the common service order; no Pay or money ledger';

CREATE TABLE ord_manual_service_admission_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    action_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    declaration_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    admission_revision BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    reason_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_action (tenant_no,action_no),
    UNIQUE KEY uk_manual_action_revision (tenant_no,declaration_no,admission_revision),
    KEY idx_manual_action_order (tenant_no,entity_no,store_no,order_no,id),
    CONSTRAINT ck_manual_action CHECK (tenant_no='MAIN' AND admission_revision>0 AND version>0 AND deleted=0
        AND action IN ('RECEIPT_RECORDED','STOCK_OBSERVED','ADMISSION_CHANGED')
        AND JSON_VALID(result_json) AND JSON_TYPE(result_json)='OBJECT')
) ENGINE=InnoDB COMMENT='Append-only original manual receipt and admission action evidence';
