-- Shop-owned earning policies. Payment accounts and historical snapshots remain untouched.
CREATE TABLE ord_points_earn_rule (
    type_code VARCHAR(64) NOT NULL,
    scope_no VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY(type_code, scope_no)
);
CREATE TABLE ord_points_earn_rule_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(100) NOT NULL,
    type_code VARCHAR(64) NOT NULL,
    scope_no VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    command_json MEDIUMTEXT NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_ord_earn_request(request_id),
    UNIQUE KEY uk_ord_earn_revision(type_code,scope_no,revision)
);
