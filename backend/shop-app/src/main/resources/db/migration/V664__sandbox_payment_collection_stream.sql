ALTER TABLE notify_message ADD COLUMN biz_type VARCHAR(32) NULL,
 ADD COLUMN biz_no VARCHAR(64) NULL, ADD COLUMN store_no VARCHAR(64) NULL,
 ADD COLUMN entity_no VARCHAR(64) NULL;
CREATE INDEX idx_notify_payment_unread ON notify_message(receiver_type,receiver_no,biz_type,is_read,entity_no,store_no);
-- MySQL9. Source/store bindings must be provisioned explicitly in isolated SANDBOX.
CREATE TABLE payment_collection_source (
 environment VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
 pay_app_id BIGINT NOT NULL, source_type VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
 source_id BIGINT NOT NULL, payment_store_id BIGINT NOT NULL,
 store_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 entity_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(environment,pay_app_id,source_type,source_id,payment_store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE payment_collection_receipt (
 environment VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
 event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 pay_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 payload_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 receipt_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, payload LONGTEXT NOT NULL,
 store_no VARCHAR(64) COLLATE utf8mb4_bin NULL, entity_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 created_at BIGINT NOT NULL, projected_at BIGINT NULL,
 PRIMARY KEY(environment,event_id), UNIQUE KEY uk_collection_order(environment,pay_order_no),
 UNIQUE KEY uk_collection_receipt(receipt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE payment_collection_scope (
 scope_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
 seq BIGINT NOT NULL DEFAULT 0, floor_seq BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE payment_collection_stream (
 scope_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
 seq BIGINT NOT NULL, event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 message_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, pay_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 paid_at BIGINT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(scope_key,seq), UNIQUE KEY uk_collection_stream_event(scope_key,event_id),
 KEY idx_collection_stream_expiry(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
