CREATE TABLE sys_ops_api_key (
    key_no CHAR(36) NOT NULL,
    staff_no VARCHAR(64) NOT NULL,
    secret_hash CHAR(64) NOT NULL,
    label VARCHAR(80) NOT NULL,
    access_level VARCHAR(16) NOT NULL,
    issued_by VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT NULL,
    PRIMARY KEY (key_no),
    UNIQUE KEY uk_ops_api_key_hash (secret_hash),
    KEY idx_ops_api_key_staff (staff_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
