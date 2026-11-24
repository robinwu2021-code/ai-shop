CREATE TABLE ivc_invoice_application (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  store_no VARCHAR(64) NOT NULL,
  application_no VARCHAR(64) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_no VARCHAR(64) NOT NULL,
  current_revision INT NOT NULL,
  status VARCHAR(24) NOT NULL,
  issued_invoice_no VARCHAR(64) NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_application_no (tenant_no, application_no),
  UNIQUE KEY uk_ivc_application_source (tenant_no, entity_no, source_type, source_no),
  KEY idx_ivc_application_store (tenant_no, entity_no, store_no, status, id)
) COMMENT='发票申请：一个开票来源一条主记录，按门店隔离';

CREATE TABLE ivc_invoice_application_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  application_no VARCHAR(64) NOT NULL,
  revision_no INT NOT NULL,
  buyer_tax_id VARCHAR(32) NULL,
  buyer_name VARCHAR(60) NULL,
  carrier_type VARCHAR(16) NULL,
  carrier_id VARCHAR(64) NULL,
  donate_code VARCHAR(16) NULL,
  currency CHAR(3) NOT NULL,
  source_amount_minor BIGINT NOT NULL,
  source_fingerprint CHAR(64) NOT NULL,
  source_snapshot JSON NOT NULL,
  submitted_by VARCHAR(64) NOT NULL,
  submitted_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_application_revision (tenant_no, application_no, revision_no)
) COMMENT='不可变的发票申请版本及服务端冻结来源快照';

CREATE TABLE ivc_invoice_application_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  store_no VARCHAR(64) NOT NULL,
  application_no VARCHAR(64) NOT NULL,
  revision_no INT NOT NULL,
  action VARCHAR(24) NOT NULL,
  from_status VARCHAR(24) NULL,
  to_status VARCHAR(24) NOT NULL,
  actor_no VARCHAR(64) NOT NULL,
  reason VARCHAR(256) NULL,
  command_key_hash CHAR(64) NOT NULL,
  command_payload_hash CHAR(64) NOT NULL,
  related_invoice_no VARCHAR(64) NULL,
  occurred_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_application_command (tenant_no, entity_no, store_no, action, command_key_hash),
  KEY idx_ivc_application_event (tenant_no, application_no, id)
) COMMENT='发票申请事件，保存版本变化、操作理由与命令幂等';

ALTER TABLE ivc_invoice
  ADD COLUMN application_no VARCHAR(64) NULL,
  ADD COLUMN application_revision_no INT NULL,
  ADD KEY idx_ivc_invoice_application (tenant_no, application_no, application_revision_no);
