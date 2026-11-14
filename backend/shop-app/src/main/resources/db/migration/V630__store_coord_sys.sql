-- 门店坐标的坐标系（TDD-双地图提供方与门店选点 AC5）。
--
-- 建店地图选点按门店地区走不同的地图：大陆高德是 gcj02，台湾 / 阿联酋 Google 是 wgs84。
-- 坐标原样存进 lat_e6 / lng_e6，不做转换（转成 gcj02 再画到 Google 底图上反而偏），这一列记它是哪一种。
-- 不回填：存量坐标都来自高德地理编码或高德定位，NULL 按历史口径即 gcj02。

ALTER TABLE mch_store ADD COLUMN coord_sys VARCHAR(8) NULL;
