-- Binding epochs prevent revoked KDS grants from reviving when the same device is rebound.
ALTER TABLE prn_device
 ADD COLUMN authorization_version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN revoke_reason VARCHAR(255) NULL,
 ADD COLUMN revoked_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD COLUMN revoke_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD COLUMN revocation_history MEDIUMTEXT NULL;
