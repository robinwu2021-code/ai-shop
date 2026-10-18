-- Ordinary cash refunds: commit the original reversal command before contacting a provider.
CREATE TABLE IF NOT EXISTS stl_cash_refund_reverse (
    id BIGINT NOT NULL AUTO_INCREMENT,
    after_sale_no VARCHAR(64) NOT NULL,
    settle_no VARCHAR(64) NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    command_json MEDIUMTEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    pay_channel VARCHAR(32) NOT NULL,
    provider_no VARCHAR(64) DEFAULT NULL,
    last_error VARCHAR(512) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cash_refund_reverse_as (after_sale_no),
    UNIQUE KEY uk_cash_refund_reverse_request (request_no),
    UNIQUE KEY uk_cash_refund_reverse_receipt (pay_channel,provider_no),
    KEY idx_cash_refund_reverse_bill (settle_no,state)
) COMMENT='Original cash refund split reversal and outcome recovery';
