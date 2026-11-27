ALTER TABLE sv_voucher
    ADD COLUMN origin_voucher_no VARCHAR(64) NULL,
    ADD COLUMN usage_no VARCHAR(64) NULL,
    ADD COLUMN source_decision_no VARCHAR(64) NULL,
    ADD COLUMN after_sale_no VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_sv_whole_refund (tenant_no, biz_type, origin_voucher_no),
    ADD CONSTRAINT ck_sv_refund_origin CHECK (biz_type <> 'REFUND' OR
        (origin_voucher_no IS NOT NULL AND usage_no IS NOT NULL AND source_decision_no IS NOT NULL
         AND after_sale_no IS NOT NULL AND principal_delta >= 0 AND bonus_delta >= 0
         AND (principal_delta > 0 OR bonus_delta > 0)));

ALTER TABLE sv_statement DROP CHECK sv_statement_chk_2,
    ADD CONSTRAINT ck_sv_statement_signed CHECK (
        (type IN ('RECHARGE','REFUND') AND signed_amount_minor > 0)
        OR (type='REDEMPTION' AND signed_amount_minor < 0));
