CREATE TABLE ivc_channel_application (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  application_no VARCHAR(64) NOT NULL,
  regime_code VARCHAR(16) NOT NULL,
  channel_code VARCHAR(32) NOT NULL,
  environment VARCHAR(32) NOT NULL,
  seller_tax_id VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  current_revision INT NOT NULL,
  account_no VARCHAR(64) NULL,
  request_key_hash CHAR(64) NOT NULL,
  request_payload_hash CHAR(64) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_channel_application_no (tenant_no, application_no),
  UNIQUE KEY uk_ivc_channel_application_request (tenant_no, entity_no, environment, request_key_hash),
  KEY idx_ivc_channel_application_entity (tenant_no, entity_no, environment, id)
);

CREATE TABLE ivc_channel_application_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  application_no VARCHAR(64) NOT NULL,
  revision_no INT NOT NULL,
  material_refs JSON NOT NULL,
  submitted_by VARCHAR(64) NOT NULL,
  submitted_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_channel_application_revision (tenant_no, application_no, revision_no)
);

CREATE TABLE ivc_channel_application_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  application_no VARCHAR(64) NOT NULL,
  revision_no INT NOT NULL,
  action VARCHAR(24) NOT NULL,
  to_status VARCHAR(24) NOT NULL,
  actor_no VARCHAR(64) NOT NULL,
  reason VARCHAR(256) NULL,
  occurred_at DATETIME(3) NOT NULL,
  KEY idx_ivc_channel_application_event (tenant_no, application_no, id)
);

CREATE TABLE ivc_channel_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  account_no VARCHAR(64) NOT NULL,
  regime_code VARCHAR(16) NOT NULL,
  channel_code VARCHAR(32) NOT NULL,
  environment VARCHAR(32) NOT NULL,
  seller_tax_id VARCHAR(32) NOT NULL,
  provider_account_code VARCHAR(64) NOT NULL,
  current_revision BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_channel_account_no (tenant_no, account_no),
  UNIQUE KEY uk_ivc_channel_account_provider (tenant_no, channel_code, environment, provider_account_code),
  KEY idx_ivc_channel_account_entity (tenant_no, entity_no, environment)
);

CREATE TABLE ivc_channel_account_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  account_no VARCHAR(64) NOT NULL,
  revision_no BIGINT NOT NULL,
  application_no VARCHAR(64) NOT NULL,
  seller_name VARCHAR(128) NOT NULL,
  provider_secret_ref BIGINT NOT NULL,
  qr_key_ref BIGINT NULL,
  verified_by VARCHAR(64) NOT NULL,
  verified_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_channel_account_revision (tenant_no, account_no, revision_no)
);

CREATE TABLE ivc_channel_route (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  regime_code VARCHAR(16) NOT NULL,
  environment VARCHAR(32) NOT NULL,
  active_account_no VARCHAR(64) NOT NULL,
  route_version BIGINT NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_channel_route_scope (tenant_no, entity_no, regime_code, environment)
);

CREATE TABLE ivc_channel_route_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  regime_code VARCHAR(16) NOT NULL,
  environment VARCHAR(32) NOT NULL,
  route_version BIGINT NOT NULL,
  previous_account_no VARCHAR(64) NULL,
  active_account_no VARCHAR(64) NOT NULL,
  number_range_no VARCHAR(32) NOT NULL,
  actor_no VARCHAR(64) NOT NULL,
  reason VARCHAR(256) NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_channel_route_event (tenant_no, entity_no, regime_code, environment, route_version)
);

ALTER TABLE ivc_invoice
  ADD COLUMN channel_code VARCHAR(32) NULL,
  ADD COLUMN channel_account_no VARCHAR(64) NULL,
  ADD COLUMN channel_account_revision BIGINT NULL,
  ADD COLUMN channel_route_version BIGINT NULL,
  ADD COLUMN channel_environment VARCHAR(32) NULL,
  ADD KEY idx_ivc_invoice_channel (tenant_no, entity_no, channel_account_no, id);

ALTER TABLE ivc_transmission ADD COLUMN channel_account_no VARCHAR(64) NULL;

ALTER TABLE ivc_number_range
  ADD COLUMN channel_account_no VARCHAR(64) NULL,
  ADD KEY idx_ivc_range_channel (tenant_no, entity_no, regime_code, channel_account_no, period_code, status, store_no);
