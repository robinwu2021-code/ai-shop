CREATE INDEX idx_pts_voucher_ops_cursor
    ON pts_voucher(type_code, created_at, id);

CREATE INDEX idx_pts_entry_ops_voucher_holder
    ON pts_ledger_entry(type_code, voucher_no, holder_type, holder_no, deleted);
