-- Verified callback is retained before identity routing. Old-number callbacks remain auditable but never advance a new shipment.
CREATE TABLE lgs_push_event (
    event_no CHAR(36) NOT NULL PRIMARY KEY,
    channel VARCHAR(32) NOT NULL,
    callback_id VARCHAR(64) NULL,
    push_status VARCHAR(32) NULL,
    waybill_no VARCHAR(64) NULL,
    payload LONGTEXT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    shipment_no VARCHAR(64) NULL,
    received_at DATETIME NOT NULL,
    KEY idx_lgs_push_callback (callback_id),
    KEY idx_lgs_push_waybill (channel, waybill_no, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Verified raw channel callbacks and routing decisions';
