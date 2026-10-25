-- Keep the existing PRODUCTION_REVERSE reason identity (18 characters) in exact reversals.
ALTER TABLE inv_ledger MODIFY COLUMN reason_code VARCHAR(32) NOT NULL
    COMMENT 'Original document reason snapshot; includes exact production reversal';
