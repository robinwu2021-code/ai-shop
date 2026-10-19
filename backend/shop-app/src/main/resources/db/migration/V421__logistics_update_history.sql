-- Append-only audit for active queries and subscribed callbacks. No raw callback payload or phone is exposed to B.
CREATE TABLE lgs_update_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    shipment_no VARCHAR(64) NOT NULL,
    biz_ref VARCHAR(64) NOT NULL,
    carrier VARCHAR(32) NULL,
    waybill_no VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    before_status VARCHAR(32) NULL,
    after_status VARCHAR(32) NULL,
    nodes_added INT NOT NULL DEFAULT 0,
    outcome VARCHAR(32) NOT NULL,
    detail VARCHAR(255) NULL,
    occurred_at BIGINT NOT NULL,
    KEY idx_lgs_update_biz (biz_ref, id),
    KEY idx_lgs_update_shipment (shipment_no, id),
    KEY idx_lgs_update_number (carrier, waybill_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Waybill update attempts and state changes by source';
