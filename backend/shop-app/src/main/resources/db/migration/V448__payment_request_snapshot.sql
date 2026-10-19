-- New payment attempts retain their original source coordinates. No historical proof is backfilled.
ALTER TABLE stl_payment ADD COLUMN payment_request_json MEDIUMTEXT DEFAULT NULL
    COMMENT 'Immutable original payment request and store routing snapshot';
