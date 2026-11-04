-- Declarations and private evidence bindings are commercial workflow, never a cash ledger.
CREATE TABLE mall_offline_evidence (
    tenant_no VARCHAR(32) NOT NULL,
    asset_key VARCHAR(512) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    scope_snapshot JSON NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (tenant_no, asset_key),
    KEY idx_offline_evidence_order (tenant_no, order_no)
);
CREATE TABLE mall_offline_claim (
    tenant_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    claim_no VARCHAR(64) NOT NULL,
    claim_snapshot JSON NOT NULL,
    PRIMARY KEY (tenant_no, order_no, claim_no)
);

CREATE TABLE mall_offline_action (
    tenant_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    action_no VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    action_snapshot JSON NOT NULL,
    PRIMARY KEY (tenant_no, order_no, action_no),
    KEY idx_offline_action_pending (status, tenant_no, order_no)
);
