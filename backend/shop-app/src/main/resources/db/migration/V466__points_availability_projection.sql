-- Derived, rebuildable interval projection; never a financial truth source.
CREATE TABLE pts_availability_projection (
    type_code VARCHAR(40) NOT NULL,
    source_version BIGINT NOT NULL,
    projected_version BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (type_code)
);
CREATE INDEX idx_pts_acceptance_history ON pts_merchant_agreement(type_code,id);
CREATE INDEX idx_pts_pause_history ON pts_pause_interval(type_code,id);
