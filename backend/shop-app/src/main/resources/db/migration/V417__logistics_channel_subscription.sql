-- One row per external channel and immutable dispatch identity. Historical rows survive corrections.
CREATE TABLE lgs_channel_subscription (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    shipment_no VARCHAR(64) NOT NULL,
    identity_key VARCHAR(192) NOT NULL,
    source_shipment_no VARCHAR(64) NULL,
    source_shipment_version BIGINT NULL,
    carrier VARCHAR(16) NOT NULL,
    waybill_no VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    state VARCHAR(20) NOT NULL,
    ref VARCHAR(255) NULL,
    error VARCHAR(255) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_attempt_at BIGINT NULL,
    last_query_at BIGINT NULL,
    next_retry_at BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_lgs_channel_identity (shipment_no, identity_key, channel),
    KEY idx_lgs_channel_retry (state, next_retry_at),
    KEY idx_lgs_channel_waybill (carrier, waybill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-channel subscription status for an immutable shipment identity';

-- A reservation is spent even if the network result is unknown. The unique slot prevents >4 requests/month.
CREATE TABLE lgs_kd100_sub_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_ref VARCHAR(64) NOT NULL,
    company_code VARCHAR(32) NOT NULL,
    waybill_no VARCHAR(64) NOT NULL,
    month_key CHAR(6) NOT NULL,
    attempt_no TINYINT NOT NULL,
    shipment_no VARCHAR(64) NOT NULL,
    identity_key VARCHAR(192) NOT NULL,
    callback_id VARCHAR(64) NULL,
    result VARCHAR(20) NOT NULL,
    result_code VARCHAR(32) NULL,
    terminated_at BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_lgs_kd100_month_slot (account_ref, company_code, waybill_no, month_key, attempt_no),
    KEY idx_lgs_kd100_callback (callback_id),
    KEY idx_lgs_kd100_shipment (shipment_no, identity_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Kuaidi100 /poll attempts shared across orders';

-- Preserve an already accepted legacy subscription, without pretending that it is a WeChat follow.
INSERT INTO lgs_channel_subscription (
    shipment_no, identity_key, source_shipment_no, source_shipment_version,
    carrier, waybill_no, channel, state, ref, error, attempts, created_at, updated_at
)
SELECT w.shipment_no,
       CASE WHEN w.source_shipment_no IS NOT NULL AND w.source_shipment_version IS NOT NULL
            THEN CONCAT(w.source_shipment_no, ':', w.source_shipment_version)
            ELSE CONCAT('LEGACY:', w.carrier, ':', w.waybill_no) END,
       w.source_shipment_no, w.source_shipment_version,
       w.carrier, w.waybill_no, w.sub_channel, w.sub_state,
       w.sub_ref, w.sub_error, w.sub_attempts, NOW(), NOW()
FROM lgs_waybill w
WHERE w.sub_channel IS NOT NULL AND w.sub_channel <> '';

-- Carry over the old per-waybill monthly count into the account-wide ledger (default account).
INSERT INTO lgs_kd100_sub_attempt (
    account_ref, company_code, waybill_no, month_key, attempt_no,
    shipment_no, identity_key, result, result_code, created_at, updated_at
)
SELECT 'default', old.code, old.waybill_no, old.kd100_sub_month, slots.attempt_no,
       old.shipment_no, 'LEGACY:IMPORT',
       'LEGACY', NULL, NOW(), NOW()
FROM (
    SELECT c.code, w.waybill_no, w.kd100_sub_month,
           MIN(w.shipment_no) AS shipment_no,
           LEAST(SUM(w.kd100_sub_count), 4) AS used_count
    FROM lgs_waybill w
    -- These pre-existing columns have different utf8mb4 collations in production.
    JOIN lgs_carrier_code c ON c.carrier COLLATE utf8mb4_0900_ai_ci =
                               w.carrier COLLATE utf8mb4_0900_ai_ci
                           AND c.channel = 'kuaidi100'
    WHERE w.kd100_sub_month IS NOT NULL AND w.kd100_sub_count > 0
    GROUP BY c.code, w.waybill_no, w.kd100_sub_month
) old
JOIN (SELECT 1 AS attempt_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) slots
  ON slots.attempt_no <= old.used_count;
