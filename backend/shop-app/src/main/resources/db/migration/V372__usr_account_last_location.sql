-- 用户最后已知位置（TDD-虹选鲜果运营落地 §1 L2）。
--
-- resolve 命中后**异步 + 节流**写这里（移动超 500m 或超 N 分钟才写），精确到小区：
-- last_lat/lng（精确坐标）+ last_place（小区/楼盘名）+ last_community_no（落进的开放聚落）。
--
-- **与 community_no 不互相覆盖**：community_no 是用户**主动绑**的聚落（强信号），
-- last_* 是**被动探**得的（弱）。下单、取货仍以 community_no 为准（口径不动）；
-- last_* 只用于：冷启动/换设备兜底、运营看用户地理分布、新用户预选最近开放聚落。
--
-- 全可空：**存量用户一行都不补** —— 他们下次定位时自然写上。
-- 隐私：精确位置敏感，运营看他人 last_* 要权限位 + 留痕，对外接口/导出一律不带它。

ALTER TABLE usr_account
    ADD COLUMN last_lat_e6 INT DEFAULT NULL COMMENT '最后已知位置纬度 e6（精确，被动定位探得）',
    ADD COLUMN last_lng_e6 INT DEFAULT NULL COMMENT '最后已知位置经度 e6',
    ADD COLUMN last_region_code VARCHAR(12) DEFAULT NULL COMMENT '最后已知区县码',
    ADD COLUMN last_place VARCHAR(128) DEFAULT NULL COMMENT '最后已知小区/楼盘名（geo_place 的 AOI 级）',
    ADD COLUMN last_community_no VARCHAR(64) DEFAULT NULL COMMENT '落进的开放聚落；没落进为空。不覆盖主动绑定的 community_no',
    ADD COLUMN last_located_at DATETIME DEFAULT NULL COMMENT '最后定位时刻，给写回节流与时效判断';
