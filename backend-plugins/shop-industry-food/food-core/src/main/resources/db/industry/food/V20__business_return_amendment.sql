-- Approval intent and original-owner receipts, never a payment/refund ledger.
CREATE TABLE fnb_return_amendment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL,approval_no VARCHAR(32) NOT NULL,return_no VARCHAR(32) NOT NULL,
 actor_no VARCHAR(32) NOT NULL,operation_key VARCHAR(64) NOT NULL,request_hash VARCHAR(64) NOT NULL,source_hash VARCHAR(64) NOT NULL,
 request_snapshot JSON NOT NULL,command_snapshot JSON NULL,receipt_snapshot JSON NULL,status VARCHAR(24) NOT NULL,
 replacement_cart_no VARCHAR(32) NULL,replacement_batch_no VARCHAR(32) NULL,replacement_order_no VARCHAR(32) NULL,
 replacement_key VARCHAR(64) NULL,replacement_hash VARCHAR(64) NULL,
 created_at DATETIME NOT NULL,created_by VARCHAR(32) NOT NULL,updated_at DATETIME NOT NULL,updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,deleted INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_food_return_amendment(approval_no),UNIQUE KEY uk_food_return_approval(entity_no,store_no,return_no),
 UNIQUE KEY uk_food_return_approval_key(entity_no,store_no,operation_key),KEY ix_food_return_amendment_recovery(status,updated_at)
);
