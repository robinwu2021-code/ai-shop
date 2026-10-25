ALTER TABLE ord_industry_fulfillment ADD fulfillment_kind VARCHAR(32) NULL, ADD plan_hash VARCHAR(64) NULL, ADD command_snapshot JSON NULL;
CREATE TABLE ord_industry_fulfillment_progress (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
 entity_no VARCHAR(32) NOT NULL, sub_order_no VARCHAR(32) NOT NULL, order_item_id BIGINT NOT NULL,
 source_component_ref VARCHAR(64) NOT NULL, fulfilled_qty DECIMAL(24,6) NOT NULL,
 unit VARCHAR(16) NOT NULL, plan_hash VARCHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,updated_by VARCHAR(32) NULL,
 version BIGINT NOT NULL DEFAULT 0,deleted INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_industry_fulfill_progress(tenant_no,entity_no,sub_order_no,order_item_id,source_component_ref)
);
