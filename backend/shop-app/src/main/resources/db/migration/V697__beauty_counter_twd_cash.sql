-- Extend the existing beauty manual counter ledger to Taiwan cash, in scale-2 minor units.
ALTER TABLE ord_checkout_confirmation
    DROP CHECK ck_checkout_confirm_money,
    ADD CONSTRAINT ck_checkout_confirm_money CHECK (
        amount_minor >= 0 AND purpose = 'ORDINARY_SERVICE_PURCHASE' AND
        (currency = 'CNY' OR (currency = 'TWD' AND amount_minor > 0 AND MOD(amount_minor, 100) = 0
            AND admission_kind = 'MANUAL_OFFLINE' AND purchase_authority = 'MERCHANT_RECORDED_IN_PERSON'))
    );

ALTER TABLE ord_manual_service_admission
    DROP CHECK ck_manual_money,
    ADD CONSTRAINT ck_manual_money CHECK (
        declared_minor > 0 AND receipt_provenance = 'MANUAL_DECLARATION' AND
        ((currency = 'CNY' AND method IN ('CASH', 'OFFLINE_TRANSFER')) OR
            (currency = 'TWD' AND method = 'CASH' AND MOD(declared_minor, 100) = 0))
    );
