-- 可见范围：多边形与显式「不限」（ADR-034 / TDD-可见范围分级匹配与多边形）
--
-- 1) mch_service_area 加 geometry：level=POLYGON 的范围项把规范化顶点存这里；其余 level 为 NULL。
--    level 的取值在此之后多三种：PROVINCE（省，2 位区划码）、POLYGON（地图多边形，ref_code=几何指纹）、
--    UNLIMITED（全平台不限，ref_code='*'）。列本身是 VARCHAR，不改定义；注释见实体 MchServiceArea。
-- 2) (store_no, mode) 索引：排除项按店定位用。
-- 3) mch_service_area_cell：多边形的 S2 网格派生表。查询时消费者坐标 → 各级 cell token → cell_id IN (...)，
--    与行政级的 ref_code IN (祖先码) 同一种索引形状。boundary=1 的 cell 命中后还要用 geometry 精判。
--    它是派生数据：可由 mch_service_area.geometry 全量重建，走物理删除。
-- H2 / MySQL 共用一份：不写排序规则、不用 MODIFY COLUMN 改注释。

ALTER TABLE mch_service_area
    ADD COLUMN geometry TEXT NULL COMMENT 'level=POLYGON 时的顶点 JSON [[lngE6,latE6],...]（规范化、首尾不重复）；其余为 NULL';

CREATE INDEX idx_service_area_store_mode ON mch_service_area (store_no, mode);

CREATE TABLE IF NOT EXISTS mch_service_area_cell
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    area_no    VARCHAR(64) NOT NULL COMMENT '所属多边形范围项 mch_service_area.area_no',
    entity_no  VARCHAR(64) NOT NULL COMMENT '商家主体（随范围项）',
    store_no   VARCHAR(64) NOT NULL COMMENT '门店（随范围项）',
    mode       VARCHAR(16) NOT NULL COMMENT '随多边形：INCLUDE / EXCLUDE',
    cell_id    VARCHAR(32) NOT NULL COMMENT 'S2 cell token',
    s2_level   TINYINT     NOT NULL COMMENT 'S2 级别',
    boundary   TINYINT     NOT NULL DEFAULT 0 COMMENT '1=边界 cell，命中后还要用多边形精判；0=内部 cell，命中即在内',
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)          DEFAULT NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)          DEFAULT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '恒为 0 —— 派生表走物理删除，这一列只为与 BaseEntity 对齐',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sac_area_cell (area_no, cell_id),
    KEY idx_sac_cell (cell_id),
    KEY idx_sac_store (store_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='多边形范围的 S2 网格派生表（可由 mch_service_area.geometry 重建）';
