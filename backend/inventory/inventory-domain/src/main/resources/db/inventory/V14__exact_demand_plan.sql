-- Exact quantities remain in the original inventory balance, documents and immutable ledger.
ALTER TABLE inv_stock_balance MODIFY on_hand DECIMAL(24,6) NOT NULL DEFAULT 0, MODIFY reserved DECIMAL(24,6) NOT NULL DEFAULT 0;
ALTER TABLE inv_ledger MODIFY qty_delta DECIMAL(24,6) NOT NULL, MODIFY balance_after DECIMAL(24,6) NOT NULL;
ALTER TABLE inv_outbound_line MODIFY qty DECIMAL(24,6) NOT NULL;
ALTER TABLE inv_outbound_order MODIFY total_qty DECIMAL(24,6) NOT NULL, MODIFY total_cost_minor BIGINT NULL;
ALTER TABLE inv_ledger ADD cost_currency VARCHAR(3) NULL;
ALTER TABLE inv_item ADD cost_currency VARCHAR(3) NULL COMMENT 'Inventory owner valuation currency, unknown remains null';
CREATE TABLE inv_demand_plan (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id VARCHAR(32) NOT NULL,
 external_ref VARCHAR(64) NOT NULL, plan_no VARCHAR(32) NOT NULL, plan_hash VARCHAR(64) NOT NULL,
 plan_snapshot JSON NOT NULL, lifecycle_version INT NOT NULL, status VARCHAR(24) NOT NULL,
 expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by VARCHAR(64) NOT NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_demand_scope(owner_id,external_ref), UNIQUE KEY uk_demand_no(owner_id,plan_no)
);
CREATE TABLE inv_demand_leaf (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id VARCHAR(32) NOT NULL, plan_no VARCHAR(32) NOT NULL,
 leaf_ref VARCHAR(64) NOT NULL, component_ref VARCHAR(64) NOT NULL, fulfillment_ref VARCHAR(64) NOT NULL,
 branch_kind VARCHAR(24) NOT NULL, item_id VARCHAR(32) NOT NULL, location_id VARCHAR(32) NOT NULL,
 qty DECIMAL(24,6) NOT NULL, issued_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
 released_qty DECIMAL(24,6) NOT NULL DEFAULT 0, unit VARCHAR(16) NOT NULL, definition_hash VARCHAR(64) NOT NULL,
 document_no VARCHAR(32) NULL, ledger_ref VARCHAR(32) NULL, cost_minor BIGINT NULL, currency VARCHAR(3) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_demand_leaf(owner_id,plan_no,leaf_ref)
);
CREATE TABLE inv_demand_operation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id VARCHAR(32) NOT NULL, plan_no VARCHAR(32) NOT NULL,
 operation_ref VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL, kind VARCHAR(24) NOT NULL,
 original_operation_ref VARCHAR(64) NULL, receipt_snapshot JSON NOT NULL, delta_snapshot JSON NOT NULL,
 reversed TINYINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by VARCHAR(64) NOT NULL, UNIQUE KEY uk_demand_operation(owner_id,operation_ref)
);

CREATE TABLE inv_production_operation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL, event_no VARCHAR(32) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 plan_no VARCHAR(32) NOT NULL, operation_ref VARCHAR(64) NOT NULL,
 receipt_snapshot JSON NOT NULL, reverse_event_no VARCHAR(32) NULL, reverse_hash VARCHAR(64) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_production_event(owner_id,store_no,event_no), UNIQUE KEY uk_production_reverse(owner_id,store_no,reverse_event_no)
);

ALTER TABLE inv_ledger ADD batch_no VARCHAR(32) NULL, ADD lot_ref VARCHAR(32) NULL;
ALTER TABLE inv_inbound_line MODIFY qty DECIMAL(24,6) NOT NULL;
ALTER TABLE inv_inbound_order MODIFY total_qty DECIMAL(24,6) NOT NULL, MODIFY total_cost_minor BIGINT NULL;
CREATE TABLE inv_stock_lot (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, lot_ref VARCHAR(32) NOT NULL,
 owner_id VARCHAR(32) NOT NULL,item_id VARCHAR(32) NOT NULL,location_id VARCHAR(32) NOT NULL,
 batch_no VARCHAR(32) NOT NULL,qty DECIMAL(24,6) NOT NULL,unit_cost_minor BIGINT NULL,currency VARCHAR(3) NULL,
 expire_at DATE NULL,source_doc_no VARCHAR(32) NOT NULL,source_line_no INT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_stock_lot(owner_id,lot_ref),UNIQUE KEY uk_stock_lot_source(owner_id,source_doc_no,source_line_no),
 KEY ix_stock_lot_pick(owner_id,item_id,location_id,expire_at)
);

ALTER TABLE inv_item ADD valuation_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE inv_item_valuation_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,owner_id VARCHAR(32) NOT NULL,item_id VARCHAR(32) NOT NULL,
 operation_key VARCHAR(64) NOT NULL,request_hash VARCHAR(64) NOT NULL,before_snapshot JSON NOT NULL,
 after_snapshot JSON NOT NULL,actor_no VARCHAR(32) NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by VARCHAR(64) NOT NULL,UNIQUE KEY uk_item_valuation_operation(owner_id,operation_key)
);
