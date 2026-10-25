-- Physical fee sources reference CORE rules; this table contains no fee prices or taxes.
CREATE TABLE fnb_fee_source_binding (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 binding_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 check_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 binding_scope VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 rule_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 revision_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,unit VARCHAR(16) NOT NULL,package_quantity DECIMAL(18,6) NULL,
 basis_source_refs JSON NOT NULL,waiver_evidence VARCHAR(255) NULL,enabled BOOLEAN NOT NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,created_by VARCHAR(32) NOT NULL,updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,deleted INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_food_fee_binding (binding_no),UNIQUE KEY uk_food_fee_binding_scope (entity_no,store_no,binding_scope,rule_no),
 CHECK (kind IN ('TEA','HOTPOT','PACKAGING','SERVICE','MINIMUM')),
 CHECK (package_quantity IS NULL OR package_quantity>0)
) ENGINE=InnoDB;

ALTER TABLE fnb_fee_source_binding ADD COLUMN contract_scope_no VARCHAR(32) NULL;
CREATE TABLE fnb_fee_contract_claim (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,cart_no VARCHAR(32) NOT NULL,cart_version BIGINT NOT NULL,
 rule_no VARCHAR(32) NOT NULL,revision_no VARCHAR(32) NOT NULL,scope_no VARCHAR(32) NOT NULL,kind VARCHAR(16) NOT NULL,
 quantity DECIMAL(20,6) NOT NULL,source_hash CHAR(64) NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_food_fee_cart_contract(entity_no,store_no,cart_no,cart_version,rule_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
