-- Ordinary chain marketing. Published scopes and cost versions are immutable owner facts.
CREATE TABLE mbr_brand_audience_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 snapshot_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, management_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 brand_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, legal_entity_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, resource_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 resource_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, planned_count INT NOT NULL, pre_skipped_count INT NOT NULL,
 pre_skip_json JSON NOT NULL, recipients_json JSON NOT NULL, member_facts_json JSON NOT NULL,
 expires_at DATETIME(6) NOT NULL, claimed_command_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, created_by VARCHAR(64) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_brand_audience_snapshot(tenant_no,snapshot_no),
 CONSTRAINT ck_brand_audience_counts CHECK(planned_count>=0 AND pre_skipped_count>=0 AND pre_skipped_count<=planned_count
 AND planned_count=pre_skipped_count+JSON_LENGTH(recipients_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE pmt_brand_coupon_source (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 source_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,management_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 brand_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,entity_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,status VARCHAR(24) NOT NULL,definition_hash CHAR(64) NOT NULL,
 source_json JSON NOT NULL,cost_rule_version_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 native_template_no VARCHAR(64) COLLATE utf8mb4_bin NULL,native_version_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 native_revision BIGINT NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 0,deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL,updated_at DATETIME(6) NOT NULL,created_by VARCHAR(64) NOT NULL,updated_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_brand_coupon_source(tenant_no,source_no),UNIQUE KEY uk_brand_coupon_template(tenant_no,native_template_no),
 UNIQUE KEY uk_brand_coupon_version(tenant_no,native_version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE pmt_brand_coupon_command (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 command_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,namespace_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 operation_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 management_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,brand_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 entity_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 action VARCHAR(32) NOT NULL,request_hash CHAR(64) NOT NULL,source_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 status VARCHAR(24) NOT NULL,request_json JSON NOT NULL,checkpoint_json JSON NOT NULL,response_json JSON NULL,
 version BIGINT NOT NULL DEFAULT 0,deleted TINYINT NOT NULL DEFAULT 0,created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,created_by VARCHAR(64) NOT NULL,updated_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_brand_coupon_command(tenant_no,command_no),UNIQUE KEY uk_brand_coupon_namespace(tenant_no,namespace_hash),
 UNIQUE KEY uk_brand_coupon_operation(tenant_no,operation_hash),
 KEY ix_brand_coupon_commands(tenant_no,management_no,brand_no,action,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Only synchronous Core commands use this receipt; remote coupon intents have their own staged owner table.
CREATE TABLE pmt_brand_marketing_command (
 tenant_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, command_id CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 management_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, brand_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 entity_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 action VARCHAR(32) NOT NULL, request_hash CHAR(64) NOT NULL, response_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(tenant_no,command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE pmt_brand_cost_rule (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL, management_no VARCHAR(64) NOT NULL, brand_no VARCHAR(64) NOT NULL,
 issuer_entity_no VARCHAR(64) NOT NULL, rule_version_no VARCHAR(64) NOT NULL,
 title VARCHAR(100) NOT NULL, currency VARCHAR(8) NOT NULL,
 headquarters_bp INT NOT NULL, store_bp INT NOT NULL, status VARCHAR(16) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 published_at DATETIME(3) NULL,
 UNIQUE KEY uk_brand_cost_rule (tenant_no,rule_version_no),
 KEY ix_brand_cost_rules (tenant_no,management_no,brand_no,issuer_entity_no),
 CHECK (headquarters_bp>=0 AND store_bp>=0 AND headquarters_bp+store_bp=10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE pmt_brand_cost_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL, management_no VARCHAR(64) NOT NULL, brand_no VARCHAR(64) NOT NULL,
 issuer_entity_no VARCHAR(64) NOT NULL, resource_type VARCHAR(16) NOT NULL,
 resource_no VARCHAR(64) NOT NULL, resource_version VARCHAR(64) NOT NULL,
 rule_version_no VARCHAR(64) NOT NULL, currency VARCHAR(8) NOT NULL,
 headquarters_bp INT NOT NULL, store_bp INT NOT NULL, stores_json JSON NOT NULL,
 snapshot_hash CHAR(64) NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_brand_cost_snapshot (tenant_no,resource_type,resource_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE pmt_brand_cost_source (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL, source_type VARCHAR(16) NOT NULL, source_no VARCHAR(64) NOT NULL,
 resource_version VARCHAR(64) NOT NULL, order_no VARCHAR(64) NOT NULL, sub_order_no VARCHAR(64) NOT NULL,
 consumer_entity_no VARCHAR(64) NOT NULL, store_no VARCHAR(64) NOT NULL,
 total_minor BIGINT NOT NULL, basis_json JSON NOT NULL, refund_state_json JSON NULL, payload_hash CHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_brand_cost_source (tenant_no,source_type,source_no),
 KEY ix_brand_cost_source_version (tenant_no,resource_version),
 CHECK (total_minor>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE pmt_brand_cost_statement (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL, management_no VARCHAR(64) NOT NULL, brand_no VARCHAR(64) NOT NULL,
 issuer_entity_no VARCHAR(64) NOT NULL, expense_entity_no VARCHAR(64) NOT NULL,
 statement_no VARCHAR(64) NOT NULL, currency VARCHAR(8) NOT NULL, through_at DATETIME(3) NOT NULL,
 status VARCHAR(16) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 total_minor BIGINT NOT NULL, headquarters_minor BIGINT NOT NULL, store_minor BIGINT NOT NULL,
 facts_json JSON NULL, created_at DATETIME(3) NOT NULL, confirmed_at DATETIME(3) NULL, created_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_brand_cost_statement (tenant_no,statement_no),
 KEY ix_brand_cost_statement_owner (tenant_no,management_no,brand_no,expense_entity_no),
 CHECK (total_minor=headquarters_minor+store_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE pmt_brand_cost_fact (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL, source_type VARCHAR(16) NOT NULL, source_no VARCHAR(64) NOT NULL,
 event_no VARCHAR(64) NOT NULL, event_kind VARCHAR(16) NOT NULL,
 expense_entity_no VARCHAR(64) NOT NULL, amount_minor BIGINT NOT NULL,
 headquarters_minor BIGINT NOT NULL, store_minor BIGINT NOT NULL,
 occurred_at DATETIME(3) NOT NULL, statement_no VARCHAR(64) NULL,
 UNIQUE KEY uk_brand_cost_fact (tenant_no,source_type,source_no,event_no,expense_entity_no),
 KEY ix_brand_cost_fact_statement (tenant_no,statement_no),
 CHECK (amount_minor=headquarters_minor+store_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
