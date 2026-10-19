-- Incoming refund/collection cash stays owned by its committed business preparation.
ALTER TABLE pts_fund_evidence ADD COLUMN binding_purpose VARCHAR(40) DEFAULT NULL;
ALTER TABLE pts_fund_evidence ADD COLUMN binding_ref VARCHAR(120) DEFAULT NULL;
