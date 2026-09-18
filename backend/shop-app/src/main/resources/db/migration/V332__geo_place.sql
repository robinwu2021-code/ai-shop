-- 固定地址库：某个坐标格子叫什么名字，上次核对是什么时候。
--
-- **这是我们自己的地名库，不是「某一家地图的结果缓存」。** 它按坐标匹配，
-- 与谁帮我们认出这个名字无关 —— 今天是高德，明天换谁、或者由运营直接录入，
-- 这张表的形状与用法都不变。所以**不留第三方的原始返回**：留原文会把它
-- 变成「某一家的结果副本」，既多一层授权问题，也会让人以为换厂商时它作废。
-- 它不作废，它是我们的。
--
-- 它同时是三样东西，而这三样本来就该是同一份数据：
--   1. 回答「我在哪」的答案库；
--   2. 防止频繁调地图的挡板（命中就不出去问）；
--   3. 逐步长大的自有资产（问过一次就永远记着，地图挂了也还在）。
-- 做成「缓存表」+「固定地址表」两层的话，同一个地方会有两条记录、两个时间戳，
-- 而它们不一致时没有任何人会发现。
CREATE TABLE IF NOT EXISTS geo_place
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    geo_key VARCHAR(16) NOT NULL COMMENT 'geohash 精度 8（约 38m×19m）—— 建筑级够用，同一栋楼里所有人命中同一行',
    lat_e6 INT(11) NOT NULL COMMENT '这个格子里首次落库的那个点，不是格子中心',
    lng_e6 INT(11) NOT NULL,
    name VARCHAR(128) NOT NULL COMMENT '最具体的那个名字：建筑 > 小区/楼盘 > 街道门牌',
    kind VARCHAR(16) NOT NULL COMMENT 'POI/AOI/STREET/REGION —— 说清这个名字是哪一档，端上据此决定要不要再问一层',
    address VARCHAR(255) DEFAULT NULL,
    region_code VARCHAR(12) DEFAULT NULL,
    township VARCHAR(64) DEFAULT NULL,
    verified_at DATETIME NOT NULL COMMENT '上次核对的时刻。超期只意味着「该回头核一次了」，不是作废：先用旧的，后台再刷',
    hit_count INT(11) NOT NULL DEFAULT 0 COMMENT '沉淀成聚落的依据：用得最多的那些地方值得我们自己认识',
    last_hit_at DATETIME DEFAULT NULL,
    promoted_no VARCHAR(64) DEFAULT NULL COMMENT '已升级成聚落的话指过去；指了就说明这一行不再是唯一答案',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_geo_place_key (geo_key),
    KEY idx_geo_place_promote (kind,hit_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='固定地址库：坐标格子到地名';
