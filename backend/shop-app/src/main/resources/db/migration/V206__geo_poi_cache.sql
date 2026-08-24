-- 地图小区结果的缓存（一片一行，结果整块存 JSON）。
--
-- 为什么要它：`sys_region` 到第五级（村/居委会）就到头了，**小区这一级库里根本没有**，
-- 只能现问高德。于是商家每进一次某个社区就等一次网络（真机上 1–2 秒），
-- 而同一个社区被同一个人反复进出是常态。
--
-- 为什么整块存 JSON 而不是一条小区一行：这张表要回答的问题只有一个 ——
-- 「这一片有哪些小区」。摊成行式表就要连带解决去重、跨片同名、增量更新与
-- 全局搜索的排序，那是另一件事（见 B端经营范围选择器-交互方案 §缓存）。
-- 需要按名字全局搜小区的那天再摊开，这张表就是现成的数据源。
--
-- `item_count` 单独一列而不是每次解 JSON 数长度：列表要在**上一级**的每一行上
-- 预告「12 个小区 / 暂无小区」，那是一次批量查询，解 JSON 等于把整片结果都读出来。
CREATE TABLE IF NOT EXISTS geo_poi_cache (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    scope_code  VARCHAR(16)  NOT NULL COMMENT '这一片的区划码：街道 9 位或村/社区 12 位',
    parent_code VARCHAR(16)  NOT NULL COMMENT '上一级码，按街道批量取下辖各社区的条数用',
    kind        VARCHAR(16)  NOT NULL DEFAULT 'ESTATE' COMMENT '缓存的是哪一类地点，现在只有小区',
    source      VARCHAR(16)  NOT NULL DEFAULT 'AMAP' COMMENT '数据来源，将来换供应商靠它区分',
    payload     MEDIUMTEXT   NOT NULL COMMENT '归一后的数组 JSON：[{name,address,latE6,lngE6,poiId}]',
    item_count  INT          NOT NULL DEFAULT 0 COMMENT '条数，给上一级的行做预告',
    fetched_at  DATETIME     NOT NULL COMMENT '这一片最后一次问地图的时刻，TTL 与刷新看它',
    tenant_no   VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64)           COMMENT '写入方：SYSTEM（后端抓）或商家号（App 回传）',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64),
    version     BIGINT       NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_geo_poi_scope (scope_code, kind),
    KEY idx_geo_poi_parent (parent_code, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '地图地点缓存（一片一行，结果存 JSON）';
