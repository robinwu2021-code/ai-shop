-- 发票打印（TDD-发票模块 §9）：交易明细与折让证明单在开立 / 折让当下冻结，打印中心只读不重算。
-- LONGTEXT 而非 JSON：MySQL 会重排 JSON 列的键，哈希就对不上（同 proof_snapshot）。

ALTER TABLE ivc_invoice
  ADD COLUMN detail_snapshot LONGTEXT NULL COMMENT '交易明细冻结内容',
  ADD COLUMN detail_snapshot_hash CHAR(64) NULL,
  ADD CONSTRAINT ck_ivc_invoice_detail_json CHECK (detail_snapshot IS NULL OR JSON_VALID(detail_snapshot));

ALTER TABLE ivc_allowance
  ADD COLUMN print_snapshot LONGTEXT NULL COMMENT '折让证明单冻结内容',
  ADD COLUMN print_snapshot_hash CHAR(64) NULL,
  ADD CONSTRAINT ck_ivc_allowance_print_json CHECK (print_snapshot IS NULL OR JSON_VALID(print_snapshot));
