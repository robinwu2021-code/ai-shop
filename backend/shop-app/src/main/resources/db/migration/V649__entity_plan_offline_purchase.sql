-- Entity quota package purchase: deliberately separate from per-store mall entitlements.
-- No live prices or bank accounts are seeded. Money remains authoritative in stl_payment.
CREATE TABLE mch_plan_offer (
  tenant_no VARCHAR(32) NOT NULL,
  offer_no VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  snapshot_json JSON NOT NULL,
  PRIMARY KEY (tenant_no,offer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE mch_plan_purchase (
  tenant_no VARCHAR(32) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  buyer_no VARCHAR(64) NOT NULL,
  buyer_kind VARCHAR(16) NOT NULL,
  request_no VARCHAR(64) NOT NULL,
  request_json JSON NOT NULL,
  snapshot_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  open_entity VARCHAR(64) DEFAULT NULL,
  payment_no VARCHAR(64) DEFAULT NULL,
  closure_json JSON DEFAULT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (tenant_no,order_no),
  UNIQUE KEY uk_plan_request(tenant_no,buyer_kind,buyer_no,request_no),
  UNIQUE KEY uk_plan_open_entity(tenant_no,open_entity),
  KEY idx_plan_entity(tenant_no,entity_no,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE mch_plan_purchase_claim (
  tenant_no VARCHAR(32) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  claim_no VARCHAR(64) NOT NULL,
  command_json JSON NOT NULL,
  status VARCHAR(24) NOT NULL,
  review_note VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (tenant_no,order_no,claim_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Accepted commands are the recovery journal, not an additional money ledger or generic outbox.
CREATE TABLE mch_plan_purchase_action (
  tenant_no VARCHAR(32) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  action_no VARCHAR(64) NOT NULL,
  operator_no VARCHAR(64) NOT NULL,
  command_json JSON NOT NULL,
  confirmed_at BIGINT NOT NULL,
  kind VARCHAR(16) NOT NULL DEFAULT 'CONFIRM',
  status VARCHAR(32) NOT NULL,
  payment_no VARCHAR(64) DEFAULT NULL,
  failure_code VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (tenant_no,order_no,action_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE mch_plan_purchase_evidence (
  tenant_no VARCHAR(32) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  asset_key VARCHAR(512) NOT NULL,
  submitter_no VARCHAR(64) NOT NULL,
  subject_kind VARCHAR(16) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  PRIMARY KEY (tenant_no,asset_key),
  KEY idx_plan_evidence_order(tenant_no,order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Repair only missing definition rows using already-approved V150 + V221 defaults.
-- Never overwrite an operator's existing definition, subscription snapshot or override.
-- These are capacity definitions, not prices: no paid offer is enabled by this migration.
INSERT INTO sys_merchant_plan_def
(plan_code,name,store_quota,staff_quota,cross_store_stats,trial_days,enabled,sort,tenant_no,created_at,updated_at,version,deleted)
SELECT 'FREE','孵化版',3,0,0,0,1,10,'MAIN',NOW(),NOW(),0,0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def WHERE plan_code='FREE');
INSERT INTO sys_merchant_plan_def
(plan_code,name,store_quota,staff_quota,cross_store_stats,trial_days,enabled,sort,tenant_no,created_at,updated_at,version,deleted)
SELECT 'PRO','成长版',10,3,1,14,1,20,'MAIN',NOW(),NOW(),0,0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def WHERE plan_code='PRO');
INSERT INTO sys_merchant_plan_def
(plan_code,name,store_quota,staff_quota,cross_store_stats,trial_days,enabled,sort,tenant_no,created_at,updated_at,version,deleted)
SELECT 'CHAIN','连锁版',30,15,1,14,1,30,'MAIN',NOW(),NOW(),0,0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def WHERE plan_code='CHAIN');
