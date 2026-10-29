-- Immutable original RETAIL physical handover deltas; no history backfill or financial/inventory state change.
CREATE TABLE ord_channel_fulfillment_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), created_by VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_by VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
    entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL, source_order_ref VARCHAR(64) NOT NULL,
    order_no VARCHAR(32) NOT NULL, sub_order_no VARCHAR(32) NOT NULL, fulfillment_no VARCHAR(32) NOT NULL,
    revision_no VARCHAR(32) NOT NULL, receipt_no VARCHAR(32) NOT NULL, stage VARCHAR(24) NOT NULL,
    source_hash CHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL, plan_hash CHAR(64) NOT NULL,
    inventory_receipt_hash CHAR(64) NOT NULL, content_hash CHAR(64) NOT NULL,
    payload_snapshot LONGTEXT NOT NULL, occurred_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_channel_fulfillment_action(tenant_no,fulfillment_no,revision_no),
    UNIQUE KEY uk_channel_fulfillment_receipt(tenant_no,receipt_no),
    KEY idx_channel_fulfillment_source(tenant_no,entity_no,store_no,source_order_ref,order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
