-- Local FOOD table appointments never assert a shared resource hold or paid deposit.
CREATE TABLE fnb_reservation_table (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
    entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    reservation_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    table_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_table (entity_no, reservation_no, table_no, deleted),
    KEY ix_reservation_table_conflict (entity_no, store_no, table_no, deleted)
) ENGINE=InnoDB COMMENT='FOOD local bounded table reservation membership';
CREATE INDEX ix_reservation_window ON fnb_reservation (entity_no, store_no, status, end_at, start_at);
ALTER TABLE fnb_reservation
    ADD COLUMN request_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_hash VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_reservation_request (entity_no, store_no, request_key);
