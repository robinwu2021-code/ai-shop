-- 商品可见性改为查询时关联（docs/technical/design/方案-商品可见性改查询时关联.md 步骤 3）。
--
-- 买家在某个小区能看到哪些货、由哪家店提供，改由 GoodsVisibility 在查询时按
-- 商品 / 店级货架 / 门店范围现算；设置只改它自己那一行。
-- 社区池是那几行设置在两万多个小区上的展开（线上 53 万行），不再有任何读者与写者。
-- 系统未上线，历史数据可删（2026-10-07 用户确认）。
DROP TABLE IF EXISTS prd_community_pool;

-- 门店送货子集里引用了已不存在范围项的行（线上 4 行，指向 SVA202610052039310171173）。
-- 删除范围项时同事务清理引用（MerchantStoreServiceImpl#replaceAreas）是这次补上的，
-- 这里清掉补上之前留下的。两列在生产上同为 utf8mb4_unicode_520_ci，比较不会 1267。
DELETE FROM mch_channel_area
WHERE NOT EXISTS (SELECT 1 FROM mch_service_area s WHERE s.area_no = mch_channel_area.area_no);
