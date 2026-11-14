-- Preserve the exact original local-stock request before an independent FOOD call.
CREATE TABLE ord_food_stock_bridge_command (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL, entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 plan_no VARCHAR(32) NOT NULL, action VARCHAR(32) NOT NULL, operation_key VARCHAR(128) NOT NULL,
 intent_hash CHAR(64) NOT NULL, command_json LONGTEXT NOT NULL, payload_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_ord_food_stock_bridge_command(tenant_no,entity_no,store_no,plan_no,action,operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
