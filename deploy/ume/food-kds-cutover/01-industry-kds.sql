-- Run once on the target industry schema before importing legacy KDS data.
-- Keep the target surrogate keys and retain the runtime's original business keys.
ALTER TABLE fnb_station
    MODIFY tenant_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
    MODIFY entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY name VARCHAR(64) NOT NULL,
    MODIFY station_type VARCHAR(24) NOT NULL,
    MODIFY enabled TINYINT NOT NULL,
    MODIFY published_revision_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    MODIFY created_at DATETIME(3) NOT NULL,
    MODIFY updated_at DATETIME(3) NOT NULL,
    MODIFY created_by VARCHAR(32) NOT NULL,
    MODIFY updated_by VARCHAR(32) NOT NULL,
    MODIFY version BIGINT NOT NULL DEFAULT 0,
    DROP INDEX uk_food_1,
    ADD UNIQUE KEY uk_food_1 (station_no);

ALTER TABLE fnb_kds_event
    MODIFY entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY sequence_no BIGINT NOT NULL,
    MODIFY event_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    MODIFY event_type VARCHAR(40) NOT NULL,
    MODIFY source_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY source_version BIGINT NOT NULL,
    MODIFY payload MEDIUMTEXT NOT NULL,
    MODIFY occurred_at DATETIME(3) NOT NULL,
    DROP INDEX uk_kds_source,
    ADD UNIQUE KEY uk_kds_source (entity_no, store_no, event_no),
    ADD UNIQUE KEY uk_kds_sequence (entity_no, store_no, sequence_no);

ALTER TABLE fnb_kds_subscription
    MODIFY subscription_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY device_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY actor_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY status VARCHAR(16) NOT NULL,
    MODIFY version BIGINT NOT NULL DEFAULT 0,
    MODIFY requested_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY revoked_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD COLUMN request_hash VARCHAR(64) NOT NULL,
    ADD COLUMN revoke_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_kds_subscription (subscription_no),
    ADD UNIQUE KEY uk_kds_grant (entity_no, store_no, requested_by, request_key);

CREATE TABLE fnb_kds_cursor (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 sequence_no BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(entity_no,store_no)
) ENGINE=InnoDB;

CREATE TABLE fnb_station_runtime (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 status VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL,
 PRIMARY KEY(entity_no,store_no,station_no)
) ENGINE=InnoDB;

CREATE TABLE fnb_kds_voice_command (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 command_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 action VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL,
 source_version BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 device_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 voice_text VARCHAR(128) NULL,
 requested_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 claim_actor VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 lease_token VARCHAR(64) NULL,
 lease_until DATETIME(3) NULL,
 response_code VARCHAR(64) NULL,
 PRIMARY KEY(command_no),
 UNIQUE KEY uk_kds_voice_key(entity_no,store_no,requested_by,request_key),
 KEY ix_kds_voice_delivery(entity_no,store_no,device_no,status,created_at)
) ENGINE=InnoDB;
