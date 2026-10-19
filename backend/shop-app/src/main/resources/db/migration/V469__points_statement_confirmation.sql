CREATE TABLE pts_statement_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    statement_no VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    merchant_no VARCHAR(40) NOT NULL,
    store_no VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    published_at BIGINT NOT NULL,
    last_attempt_at BIGINT NOT NULL DEFAULT 0,
    notified_at BIGINT NULL,
    deadline BIGINT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_pts_statement_confirmation(statement_no,revision),
    KEY idx_pts_statement_notice(type_code,state,notified_at,last_attempt_at,id),
    KEY idx_pts_statement_due(type_code,state,deadline,id)
);
