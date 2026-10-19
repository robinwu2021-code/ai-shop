-- Rotate durable source work independently of business state, including blocked points decisions.
ALTER TABLE ord_points_refund_execution ADD COLUMN last_attempt_at BIGINT DEFAULT NULL
    COMMENT 'Last durable work selection timestamp';
CREATE INDEX ix_refund_execution_attempt ON ord_points_refund_execution(last_attempt_at,id);
