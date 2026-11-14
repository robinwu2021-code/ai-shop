-- Local NONPAY work handover evidence only; financial uncertainty is never discharged here.
ALTER TABLE fnb_close_exception
    ADD COLUMN disposition_reason VARCHAR(255) NULL COMMENT 'Operational carry reason',
    ADD COLUMN proposed_by VARCHAR(32) NULL COMMENT 'Authenticated carry proposer',
    ADD COLUMN approved_by VARCHAR(32) NULL COMMENT 'Distinct authenticated local work reviewer',
    ADD COLUMN approved_at DATETIME(3) NULL COMMENT 'UTC local NONPAY review instant';
