-- Cash orders stay on the existing path without a placement-table lookup.
ALTER TABLE ord_order ADD COLUMN points_integrated TINYINT NOT NULL DEFAULT 0;
