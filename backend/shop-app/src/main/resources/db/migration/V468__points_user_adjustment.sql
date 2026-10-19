CREATE TABLE pts_user_adjustment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    adjustment_no VARCHAR(40) NOT NULL,
    type_code VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
    submitted_by VARCHAR(40) NOT NULL,
    approved_by VARCHAR(40) NULL,
    payload_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    UNIQUE KEY uk_pts_adjustment_no(adjustment_no),
    KEY idx_pts_adjustment_page(type_code,id)
);
