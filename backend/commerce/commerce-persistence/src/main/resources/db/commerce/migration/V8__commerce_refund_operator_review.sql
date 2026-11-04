-- Buyer policy_snapshot remains immutable. Review quotes append under the owning refund lock.
ALTER TABLE mall_refund
 ADD COLUMN review_quotes_snapshot JSON NULL,
 ADD COLUMN approved_review_quote_no VARCHAR(64) NULL;
