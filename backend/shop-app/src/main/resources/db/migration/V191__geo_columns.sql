-- 高德地图能力方案 G0/G1：地址从一串字变成「坐标 + 标准化地址 + 区划码」三件套。
-- 门店此前没有坐标列：候选取货点排序、门店自取的落点都无从算。
ALTER TABLE mch_store ADD COLUMN lat_e6 INT NULL COMMENT '门店坐标（gcj02，E6）', ADD COLUMN lng_e6 INT NULL, ADD COLUMN adcode VARCHAR(12) NULL COMMENT '地理编码给的区县码（国标 6 位），与 sys_region 同口径', ADD COLUMN address_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '地址经地理编码校验并标准化';
ALTER TABLE cmt_pickup_point ADD COLUMN adcode VARCHAR(12) NULL, ADD COLUMN address_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '地址经地理编码校验';
ALTER TABLE cmt_community_apply ADD COLUMN adcode VARCHAR(12) NULL COMMENT '逆地理给的区县码', ADD COLUMN township VARCHAR(64) NULL COMMENT '逆地理给的街道/镇名，自动归属街道用';
