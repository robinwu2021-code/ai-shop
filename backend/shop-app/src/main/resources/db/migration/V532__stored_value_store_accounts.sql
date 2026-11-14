-- Account identity now includes the actual store. Historical accounts remain unassigned
-- until an audited conversion; the service rejects owners with unassigned funds.
-- No balances, vouchers or allocation history are modified by this migration.
ALTER TABLE sv_account ADD COLUMN store_no VARCHAR(64) NULL COMMENT '账户归属门店，NULL为待审计迁移历史账户' AFTER entity_no;
ALTER TABLE sv_account DROP INDEX uk_sv_owner;
ALTER TABLE sv_account ADD UNIQUE KEY uk_sv_owner (tenant_no,user_no,entity_no,store_no,currency);
ALTER TABLE sv_account ADD KEY ix_sv_store_accounts (tenant_no,entity_no,store_no,user_no);
