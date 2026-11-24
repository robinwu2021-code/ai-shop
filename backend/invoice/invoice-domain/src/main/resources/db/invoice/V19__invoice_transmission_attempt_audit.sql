-- Each execution claim advances its own fence; historical completed-attempt counters stay intact.
ALTER TABLE ivc_transmission ADD COLUMN attempt_sequence BIGINT NOT NULL DEFAULT 0;

CREATE TABLE ivc_transmission_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  store_no VARCHAR(64) NOT NULL,
  attempt_no VARCHAR(40) NOT NULL,
  transmission_no VARCHAR(32) NOT NULL,
  attempt_index BIGINT NULL,
  operation VARCHAR(32) NOT NULL,
  ref_no VARCHAR(64) NOT NULL,
  gateway_code VARCHAR(32) NOT NULL,
  channel_account_no VARCHAR(64) NULL,
  account_revision BIGINT NULL,
  actor_no VARCHAR(64) NULL,
  phase VARCHAR(16) NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  safe_code VARCHAR(32) NULL,
  payload_hash CHAR(64) NULL,
  occurred_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_attempt_phase (tenant_no, attempt_no, phase),
  UNIQUE KEY uk_ivc_attempt_sequence (tenant_no, transmission_no, attempt_index, phase),
  KEY idx_ivc_attempt_reference (tenant_no, entity_no, store_no, ref_no, id)
) COMMENT='发票挂账及查核尝试：每次执行追加开始与最终回执，不存凭据或原文';
