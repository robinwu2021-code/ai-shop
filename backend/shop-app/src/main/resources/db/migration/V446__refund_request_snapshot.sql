-- Freeze requests at intent creation. No historical cash or points proof is manufactured.
ALTER TABLE stl_payment ADD COLUMN refund_request_json MEDIUMTEXT DEFAULT NULL
    COMMENT 'Immutable original payment coordinates and refund request';
ALTER TABLE stl_payment ADD CONSTRAINT uk_stl_refund_after_sale UNIQUE (direction,after_sale_no);
