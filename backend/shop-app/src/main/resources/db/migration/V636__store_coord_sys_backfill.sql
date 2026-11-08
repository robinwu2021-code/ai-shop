-- 台湾 / 阿联酋存量门店的坐标系回填为 wgs84（TDD-商户App接口字段对齐 §2「库表 / 迁移」）。
--
-- V630 只给 mch_store 加了 coord_sys 列，没有回填；null 按历史口径当 gcj02 解释（MchStore#coordSys）。
-- 这两个市场的选点地图只有 Google（GeoRouter 默认 TW:GOOGLE,AE:GOOGLE），坐标本来就是 wgs84，
-- null 只可能来自 V630 之前建的店。不回填的话，商户 App 自画的服务范围（顶点随门店坐标系）
-- 会被按 gcj02 解释，在台湾整体偏几百米。
--
-- 只改数据、不改结构；大陆门店与已写过 coord_sys 的行不动。可重复执行。
UPDATE mch_store
SET coord_sys = 'wgs84'
WHERE coord_sys IS NULL
  AND entity_no IN (SELECT entity_no FROM mch_entity WHERE market IN ('TW', 'AE'));
