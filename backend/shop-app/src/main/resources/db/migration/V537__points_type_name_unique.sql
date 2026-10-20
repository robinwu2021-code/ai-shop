-- Give existing point types a stable operator-facing name before enforcing uniqueness.
ALTER TABLE pts_point_type ADD COLUMN name VARCHAR(80) DEFAULT NULL AFTER type_code;
UPDATE pts_point_type SET name=type_code WHERE name IS NULL OR TRIM(name)='';
ALTER TABLE pts_point_type MODIFY COLUMN name VARCHAR(80) NOT NULL;
ALTER TABLE pts_point_type ADD UNIQUE KEY uk_pts_type_name (name);
