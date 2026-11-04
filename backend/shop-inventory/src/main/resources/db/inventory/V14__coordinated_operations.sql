-- Operation identity and fencing only. Stock quantities and movements remain in the existing ledger.
CREATE TABLE inv_coordinated_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    client_code VARCHAR(32) NOT NULL,
    external_ref VARCHAR(64) NOT NULL,
    reserve_operation_no VARCHAR(64) NOT NULL,
    owner_id VARCHAR(32) DEFAULT NULL,
    item_id VARCHAR(32) DEFAULT NULL,
    location_id VARCHAR(32) DEFAULT NULL,
    mapping_version VARCHAR(64) DEFAULT NULL,
    request_reference VARCHAR(64) DEFAULT NULL,
    original_quantity INT DEFAULT NULL,
    reservation_id VARCHAR(32) DEFAULT NULL,
    released TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_coordinated_ref (client_code, external_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE inv_coordinated_shipment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    client_code VARCHAR(32) NOT NULL,
    external_ref VARCHAR(64) NOT NULL,
    operation_no VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    carrier VARCHAR(64) NOT NULL,
    tracking_no VARCHAR(128) NOT NULL,
    operator_no VARCHAR(64) NOT NULL,
    outbound_no VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_coordinated_ship (client_code, external_ref, operation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
