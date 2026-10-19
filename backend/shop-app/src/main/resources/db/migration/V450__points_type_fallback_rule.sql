-- Type-specific fallback rule metadata; previously frozen earning facts remain immutable.
ALTER TABLE pts_point_type ADD COLUMN fallback_rule_json MEDIUMTEXT DEFAULT NULL;
-- Store filtering must precede cursor paging without scanning other stores' receivables.
CREATE INDEX ix_pts_receivable_ops_store ON pts_receivable(type_code,deleted,store_no,id);
