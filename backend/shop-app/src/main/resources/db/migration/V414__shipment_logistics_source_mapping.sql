-- Order dispatch identity is independent from the generated logistics shipment number.
-- Existing rows remain explicitly unmapped; no historical package identity is inferred.
ALTER TABLE lgs_waybill
    ADD COLUMN source_shipment_no VARCHAR(64) NULL,
    ADD COLUMN source_shipment_version BIGINT NULL;

ALTER TABLE ord_shipment ADD COLUMN signed_at BIGINT NULL;

CREATE INDEX idx_lgs_source_shipment ON lgs_waybill (source_shipment_no, source_shipment_version);

-- One payment queue row retains the exact package set of its current upload request.
ALTER TABLE trd_shipping_upload ADD COLUMN source_snapshot JSON NULL;
