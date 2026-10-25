-- Durable KDS authorizations and original business-event projection. Shared prn_* remains the only printing ledger.
CREATE TABLE fnb_kds_cursor (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 sequence_no BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(entity_no,store_no)
) ENGINE=InnoDB;
CREATE TABLE fnb_kds_subscription (
 subscription_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 device_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 actor_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 stations VARCHAR(4000) NOT NULL,
 device_version BIGINT NOT NULL,
 expires_at DATETIME(3) NOT NULL,
 status VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 requested_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 revoke_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 revoke_reason VARCHAR(255) NULL,
 revoked_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 PRIMARY KEY(subscription_no),
 UNIQUE KEY uk_kds_grant(entity_no,store_no,requested_by,request_key),
 KEY ix_kds_device(entity_no,store_no,device_no,status)
) ENGINE=InnoDB;
CREATE TABLE fnb_kds_event (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 sequence_no BIGINT NOT NULL,
 event_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 event_type VARCHAR(40) NOT NULL,
 source_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 source_version BIGINT NOT NULL,
 payload MEDIUMTEXT NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 PRIMARY KEY(entity_no,store_no,sequence_no),
 UNIQUE KEY uk_kds_source(entity_no,store_no,event_no),
 KEY ix_kds_station(entity_no,store_no,station_no,sequence_no)
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
