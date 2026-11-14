-- Evidence references are nullable for existing rows. Publication fails closed without trusted proof.
ALTER TABLE sys_business_end
 ADD COLUMN client_id VARCHAR(128) NULL,
 ADD COLUMN acceptance_ref VARCHAR(128) NULL;

-- No fabricated historical snapshots: legacy rows remain null and cannot be newly offered.
ALTER TABLE mall_sku_version
 ADD COLUMN sku_name_snapshot VARCHAR(128) NULL,
 ADD COLUMN max_per_order_snapshot INT NULL;
