-- Points v3 inside pay: immutable vouchers and real U/C/D/B backing.
-- No legacy data import. Historical applied migrations are unchanged.

CREATE TABLE IF NOT EXISTS pts_point_type (
    type_code VARCHAR(40) NOT NULL,
    market VARCHAR(16) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    scale INT NOT NULL DEFAULT 2,
    rate_points BIGINT NOT NULL,
    rate_minor BIGINT NOT NULL,
    deduct_ratio_bp INT NOT NULL DEFAULT 3000,
    idle_days INT NOT NULL DEFAULT 365,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    active_market VARCHAR(16) DEFAULT NULL,
    UNIQUE KEY uk_pts_type (type_code),
    UNIQUE KEY uk_pts_active_market (active_market),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_point_type';

CREATE TABLE IF NOT EXISTS pts_fund_pool (
    type_code VARCHAR(40) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    u_minor BIGINT NOT NULL DEFAULT 0,
    c_minor BIGINT NOT NULL DEFAULT 0,
    d_minor BIGINT NOT NULL DEFAULT 0,
    b_minor BIGINT NOT NULL DEFAULT 0,
    reserved_b_minor BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_pool (type_code),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_fund_pool';

CREATE TABLE IF NOT EXISTS pts_account (
    type_code VARCHAR(40) NOT NULL,
    holder_type VARCHAR(16) NOT NULL,
    holder_no VARCHAR(64) NOT NULL,
    issued_points BIGINT NOT NULL DEFAULT 0,
    issuable_points BIGINT NOT NULL DEFAULT 0,
    pending_points BIGINT NOT NULL DEFAULT 0,
    available_points BIGINT NOT NULL DEFAULT 0,
    held_points BIGINT NOT NULL DEFAULT 0,
    refund_reserved_points BIGINT NOT NULL DEFAULT 0,
    recirculatable_points BIGINT NOT NULL DEFAULT 0,
    frozen TINYINT NOT NULL DEFAULT 0,
    expire_at BIGINT DEFAULT NULL,
    expiry_revision BIGINT NOT NULL DEFAULT 0,
    last_active_at BIGINT DEFAULT NULL,
    reminded_revision BIGINT DEFAULT NULL,
    UNIQUE KEY uk_pts_account (type_code,holder_type,holder_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_account';

CREATE TABLE IF NOT EXISTS pts_voucher (
    voucher_no VARCHAR(40) NOT NULL,
    biz_ref VARCHAR(160) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    rate_points BIGINT NOT NULL,
    rate_minor BIGINT NOT NULL,
    result_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_pts_voucher_no (voucher_no),
    UNIQUE KEY uk_pts_voucher_ref (biz_ref),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_voucher';

CREATE TABLE IF NOT EXISTS pts_ledger_entry (
    entry_no VARCHAR(40) NOT NULL,
    voucher_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    holder_type VARCHAR(16) NOT NULL,
    holder_no VARCHAR(64) NOT NULL,
    bucket VARCHAR(32) NOT NULL,
    delta_points BIGINT NOT NULL,
    after_points BIGINT NOT NULL,
    UNIQUE KEY uk_pts_entry (entry_no),
    KEY idx_pts_entry_account (type_code,holder_type,holder_no,id),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_ledger_entry';

CREATE TABLE IF NOT EXISTS pts_pool_flow (
    flow_no VARCHAR(40) NOT NULL,
    voucher_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    bucket VARCHAR(32) NOT NULL,
    delta_minor BIGINT NOT NULL,
    after_minor BIGINT NOT NULL,
    UNIQUE KEY uk_pts_pool_flow (flow_no),
    KEY idx_pts_pool_flow_type (type_code,id),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_pool_flow';

CREATE TABLE IF NOT EXISTS pts_fund_evidence (
    evidence_no VARCHAR(120) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    external_minor BIGINT NOT NULL,
    verified TINYINT NOT NULL DEFAULT 0,
    used_voucher_no VARCHAR(40) DEFAULT NULL,
    UNIQUE KEY uk_pts_evidence (evidence_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_fund_evidence';

CREATE TABLE IF NOT EXISTS pts_refund_reserve (
    reserve_no VARCHAR(40) NOT NULL,
    position_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    original_minor BIGINT NOT NULL,
    remaining_minor BIGINT NOT NULL DEFAULT 0,
    consumed_minor BIGINT NOT NULL DEFAULT 0,
    released_minor BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_reserve (reserve_no),
    UNIQUE KEY uk_pts_reserve_position (position_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_refund_reserve';

CREATE TABLE IF NOT EXISTS pts_grant_position (
    position_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    original_points BIGINT NOT NULL DEFAULT 0,
    processed_points BIGINT NOT NULL DEFAULT 0,
    payload_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_pts_grant_position_position (position_no),
    sub_order_no VARCHAR(40) DEFAULT NULL,
    user_no VARCHAR(40) DEFAULT NULL,
    UNIQUE KEY uk_pts_grant_sub (type_code,sub_order_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_grant_position';

CREATE TABLE IF NOT EXISTS pts_redeem_position (
    position_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    original_points BIGINT NOT NULL DEFAULT 0,
    processed_points BIGINT NOT NULL DEFAULT 0,
    payload_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_pts_redeem_position_position (position_no),
    sub_order_no VARCHAR(40) DEFAULT NULL,
    order_no VARCHAR(40) DEFAULT NULL,
    user_no VARCHAR(40) DEFAULT NULL,
    store_no VARCHAR(40) DEFAULT NULL,
    statement_no VARCHAR(40) DEFAULT NULL,
    ticket_no VARCHAR(40) DEFAULT NULL,
    UNIQUE KEY uk_pts_redeem_sub (type_code,sub_order_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_redeem_position';

CREATE TABLE IF NOT EXISTS pts_refund_preparation (
    position_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    original_points BIGINT NOT NULL DEFAULT 0,
    processed_points BIGINT NOT NULL DEFAULT 0,
    payload_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_pts_refund_preparation_position (position_no),
    after_sale_no VARCHAR(40) DEFAULT NULL,
    sub_order_no VARCHAR(40) DEFAULT NULL,
    input_hash VARCHAR(64) DEFAULT NULL,
    UNIQUE KEY uk_pts_refund_as (after_sale_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_refund_preparation';

CREATE TABLE IF NOT EXISTS pts_payout_ticket (
    position_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    original_points BIGINT NOT NULL DEFAULT 0,
    processed_points BIGINT NOT NULL DEFAULT 0,
    payload_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_pts_payout_ticket_position (position_no),
    provider_idem VARCHAR(80) DEFAULT NULL,
    gross_minor BIGINT NOT NULL DEFAULT 0,
    offset_minor BIGINT NOT NULL DEFAULT 0,
    cash_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(8) DEFAULT NULL,
    UNIQUE KEY uk_pts_payout_idem (provider_idem),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_payout_ticket';

CREATE TABLE IF NOT EXISTS pts_store_statement (
    position_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    original_points BIGINT NOT NULL DEFAULT 0,
    processed_points BIGINT NOT NULL DEFAULT 0,
    payload_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_pts_store_statement_position (position_no),
    store_no VARCHAR(40) DEFAULT NULL,
    period_from BIGINT DEFAULT NULL,
    period_to BIGINT DEFAULT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_statement_period (type_code,store_no,period_from),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_store_statement';

CREATE TABLE IF NOT EXISTS pts_fund_transfer (
    position_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    original_points BIGINT NOT NULL DEFAULT 0,
    processed_points BIGINT NOT NULL DEFAULT 0,
    payload_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_pts_fund_transfer_position (position_no),
    evidence_no VARCHAR(120) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_fund_transfer';

CREATE TABLE IF NOT EXISTS pts_grant_line (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    position_no VARCHAR(40) NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_pts_grant_line (position_no,item_id),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_grant_line';

CREATE TABLE IF NOT EXISTS pts_redeem_line (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    position_no VARCHAR(40) NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_pts_redeem_line (position_no,item_id),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_redeem_line';

CREATE TABLE IF NOT EXISTS pts_merchant_agreement (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    agreement_no VARCHAR(40) NOT NULL,
    store_no VARCHAR(40) NOT NULL,
    active_key VARCHAR(80) DEFAULT NULL,
    UNIQUE KEY uk_pts_agreement_no (agreement_no),
    UNIQUE KEY uk_pts_agreement_active (active_key),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_merchant_agreement';

CREATE TABLE IF NOT EXISTS pts_statement_revision (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    statement_no VARCHAR(40) NOT NULL,
    revision_no BIGINT NOT NULL,
    UNIQUE KEY uk_pts_statement_revision (statement_no,revision_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_statement_revision';

CREATE TABLE IF NOT EXISTS pts_statement_line (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    statement_no VARCHAR(40) NOT NULL,
    revision_no BIGINT NOT NULL,
    position_no VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_pts_statement_line (statement_no,revision_no,position_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_statement_line';

CREATE TABLE IF NOT EXISTS pts_receivable (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    receivable_no VARCHAR(40) NOT NULL,
    store_no VARCHAR(40) NOT NULL,
    original_minor BIGINT NOT NULL,
    recovered_minor BIGINT NOT NULL DEFAULT 0,
    locked_minor BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_receivable_no (receivable_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_receivable';

CREATE TABLE IF NOT EXISTS pts_receivable_allocation (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    ticket_no VARCHAR(40) NOT NULL,
    receivable_no VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_pts_r_allocation (ticket_no,receivable_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_receivable_allocation';

CREATE TABLE IF NOT EXISTS pts_refund_preparation_line (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    preparation_no VARCHAR(40) NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_pts_refund_line (preparation_no,item_id),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_refund_preparation_line';

CREATE TABLE IF NOT EXISTS pts_pool_request (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    request_no VARCHAR(40) NOT NULL,
    submitted_by VARCHAR(64) NOT NULL,
    approved_by VARCHAR(64) DEFAULT NULL,
    evidence_no VARCHAR(120) DEFAULT NULL,
    UNIQUE KEY uk_pts_request_no (request_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_pool_request';

CREATE TABLE IF NOT EXISTS pts_topup_plan (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    plan_no VARCHAR(40) NOT NULL,
    period_key VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_pts_plan_period (plan_no,period_key),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_topup_plan';

CREATE TABLE IF NOT EXISTS pts_switch (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    scope_no VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_pts_switch (scope,scope_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_switch';

CREATE TABLE IF NOT EXISTS pts_switch_log (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    scope_no VARCHAR(64) NOT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_switch_log';

CREATE TABLE IF NOT EXISTS pts_pause_interval (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    scope_no VARCHAR(64) NOT NULL,
    from_at BIGINT NOT NULL,
    to_at BIGINT DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_pause_interval';

CREATE TABLE IF NOT EXISTS pts_lifecycle_fact (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    sub_order_no VARCHAR(40) NOT NULL,
    source_version BIGINT NOT NULL,
    UNIQUE KEY uk_pts_lifecycle_sub (type_code,sub_order_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_lifecycle_fact';

CREATE TABLE IF NOT EXISTS pts_inbox (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    event_no VARCHAR(120) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_pts_inbox_event (event_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_inbox';

CREATE TABLE IF NOT EXISTS pts_outbox (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    event_no VARCHAR(120) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_outbox_event (event_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_outbox';

CREATE TABLE IF NOT EXISTS pts_stat_daily (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    day_key VARCHAR(10) NOT NULL,
    scope_no VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_pts_stat_daily (type_code,day_key,scope_no),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_stat_daily';

CREATE TABLE IF NOT EXISTS pts_cursor (
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    payload_json MEDIUMTEXT NOT NULL,
    task_name VARCHAR(64) NOT NULL,
    watermark BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_cursor_task (task_name),
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) COMMENT='Points v3 pts_cursor';
