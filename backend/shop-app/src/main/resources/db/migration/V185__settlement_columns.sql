-- 聚落模型落地（方案「四级树与聚落层」）：小区与村同为 cmt_community 里的聚落。
--
-- 行政区划四级到街道/镇为止；聚落挂在 L4 之下，kind 只是展示标签。
-- 本迁移只加列与回填，行为变化在服务层。

-- ── 聚落表 ─────────────────────────────────────────
ALTER TABLE cmt_community
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'ESTATE' COMMENT 'ESTATE 小区 / VILLAGE 村。只用于展示与统计口径，不参与匹配 —— 匹配一律走 region_code 前缀与 community_no，避免出现第二套分类维度' AFTER name,
    ADD COLUMN coords_source VARCHAR(16) DEFAULT NULL COMMENT 'MERCHANT 商家提报定位 / OPS 运营补录 / SEED 种子。没有它就分不清「坐标是空的」与「坐标没人核过」' AFTER lng_e6,
    ADD COLUMN origin_code VARCHAR(12) DEFAULT NULL COMMENT '村聚落对应的官方村码（12 位，来自 sys_region 村级词典）。查重 + 与国家数据可对账；小区没有官方码，留空' AFTER region_code;

-- 同一个官方村不能被开成两个聚落。列可空，MariaDB 的 UNIQUE 允许多个 NULL，小区不受影响
ALTER TABLE cmt_community
    ADD UNIQUE KEY uk_community_origin (origin_code);

-- ── 提报表 ─────────────────────────────────────────
ALTER TABLE cmt_community_apply
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'ESTATE' COMMENT '提的是小区(ESTATE)还是村(VILLAGE)。裁决通过时原样带进聚落' AFTER name,
    ADD COLUMN origin_code VARCHAR(12) DEFAULT NULL COMMENT '提报村时从词典选中的官方村码；自由输入则空' AFTER region_code,
    ADD COLUMN lat_e6 INT DEFAULT NULL COMMENT '商家提报时的定位。他正站在那儿 —— 运营在办公室补不出坐标' AFTER origin_code,
    ADD COLUMN lng_e6 INT DEFAULT NULL AFTER lat_e6;

-- ── 存量回填 ───────────────────────────────────────
-- 两条种子聚落此前挂在 6 位区县码上，比它更细的经营范围（街道级）因此永远匹配不到。
-- 按用户指示改挂到某个街道（测试数据，随便挂）：北山街道 330106002。
UPDATE cmt_community
   SET region_code = '330106002', coords_source = 'SEED'
 WHERE region_code = '330106' AND deleted = 0;
