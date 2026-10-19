-- Pending outgoing cash reserves budget without debiting real U/B balances.
ALTER TABLE pts_fund_pool ADD COLUMN locked_u_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pts_fund_pool ADD COLUMN locked_b_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pts_fund_transfer ADD COLUMN withdraw_bucket VARCHAR(8) DEFAULT NULL;
ALTER TABLE pts_fund_transfer ADD COLUMN amount_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pts_fund_transfer ADD COLUMN provider_idem VARCHAR(80) DEFAULT NULL;
CREATE UNIQUE INDEX uk_pts_transfer_provider ON pts_fund_transfer(provider_idem);
