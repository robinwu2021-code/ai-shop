-- Preserve each store-period obligation's original recipient when accounts change within a period.
ALTER TABLE pts_store_statement ADD COLUMN route_key VARCHAR(64) NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE pts_store_statement DROP INDEX uk_pts_statement_period;
CREATE UNIQUE INDEX uk_pts_statement_route_period ON pts_store_statement(type_code,store_no,period_from,route_key);
