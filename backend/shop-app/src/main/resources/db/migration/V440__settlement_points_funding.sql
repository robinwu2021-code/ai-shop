-- Separate committed points replenishment from supplier goods transfer.
ALTER TABLE stl_bill ADD COLUMN points_funding_ticket VARCHAR(40) DEFAULT NULL;
ALTER TABLE stl_bill ADD COLUMN points_funding_ready_at BIGINT DEFAULT NULL;
