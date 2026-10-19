-- A supplier payment is historical cash, not an unpaid balance that can be silently reduced.
ALTER TABLE stl_points_refund_hold
    ADD COLUMN recovery_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
CREATE INDEX idx_points_refund_recovery ON stl_points_refund_hold (recovery_status, updated_at);
