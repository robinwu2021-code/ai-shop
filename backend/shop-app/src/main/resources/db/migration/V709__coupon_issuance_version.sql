-- First-batch PMT templates accumulate face-value exposure at the rules used for issuance.
ALTER TABLE pmt_coupon
    ADD COLUMN issued_exposure_minor BIGINT NULL;

ALTER TABLE pmt_coupon_issue
    ADD COLUMN template_version_no VARCHAR(64) NULL,
    ADD COLUMN request_key VARCHAR(64) NULL,
    ADD COLUMN request_hash CHAR(64) NULL,
    ADD UNIQUE KEY uk_coupon_issue_request (coupon_no, request_key);

ALTER TABLE pmt_user_coupon
    ADD COLUMN template_version_no VARCHAR(64) NULL,
    ADD COLUMN claim_request_key VARCHAR(64) NULL,
    ADD COLUMN claim_receipt TEXT NULL,
    ADD UNIQUE KEY uk_coupon_claim_request (coupon_no, user_no, claim_request_key);
