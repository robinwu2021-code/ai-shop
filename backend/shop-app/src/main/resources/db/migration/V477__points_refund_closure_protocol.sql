-- Pin the consumer closure protocol independently from the points financial state.
ALTER TABLE ord_points_refund_execution ADD COLUMN closure_version INT NOT NULL DEFAULT 1;
ALTER TABLE ord_points_refund_execution ADD COLUMN return_coupon BOOLEAN NOT NULL DEFAULT FALSE;
