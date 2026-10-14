-- 区划坐标（高德批量补录，先跑运城与深圳）。
-- 此前 sys_region 只有名字与区划码 —— 于是「把区域名换成坐标」只能在端上实时搜、内存缓存，
-- 重启即失；而村级聚落没坐标时 withinRadius 恒 false，买家用定位永远搜不到。
ALTER TABLE sys_region ADD COLUMN lat_e6 INT NULL COMMENT '中心点纬度（gcj02，E6）', ADD COLUMN lng_e6 INT NULL COMMENT '中心点经度（gcj02，E6）', ADD COLUMN coords_source VARCHAR(16) NULL COMMENT '坐标来源：AMAP 批量补录 / MERCHANT 商家纠正 / OPS 运营录入', ADD COLUMN coords_at DATETIME NULL COMMENT '坐标写入时间，重跑批量时据此决定是否覆盖';
CREATE INDEX idx_sys_region_coords ON sys_region (level, lat_e6);
