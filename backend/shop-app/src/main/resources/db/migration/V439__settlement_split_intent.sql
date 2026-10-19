-- Immutable cash command and dispatch ownership; no business data migration.
CREATE TABLE IF NOT EXISTS stl_split_intent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_no VARCHAR(80) NOT NULL,
    settle_no VARCHAR(64) NOT NULL,
    command_json MEDIUMTEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    points_ticket VARCHAR(40) DEFAULT NULL,
    funding_state VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_split_intent_request (request_no),
    KEY idx_split_intent_bill (settle_no,id)
) COMMENT='Committed settlement split instruction and outcome recovery';
