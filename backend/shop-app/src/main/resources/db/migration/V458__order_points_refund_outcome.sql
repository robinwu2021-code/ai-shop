-- The source keeps only confirmed pay results for order-detail return totals.
ALTER TABLE ord_points_refund_binding ADD COLUMN outcome_json LONGTEXT NULL;
