-- 归因留痕记到门店（获客看板的门店维度，S1）。
--
-- 扫码两环（scan / scanUv）在 V298 之后已经能按门店拆开（mkt_store_visit.store_no），
-- 而后三环（进店 / 首次归因 / 首单）读的是这张表，它只有 entity_no ——
-- 于是看板要么整行按主体，要么前两环按店、后三环按主体，**混着算的转化率是错的**
-- （分母按店、分子按主体，每家分店都会算出偏高的转化）。
--
-- **历史行留空**，不回填。它们记录在「一主体一码」的年代，物理上分不出是哪家分店；
-- 聚合时并到该主体的默认店 —— 与旧码本身的去向、与扫码埋点的处理完全一致。
-- 猜一个门店号填进去会让「这条归因属于哪家店」变成一个看不出来的假事实。

ALTER TABLE mkt_attribution_log
    ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '归因发生在哪家门店（扫店铺码时带入）。空 = 该主体级/历史行，聚合时并入默认店';

-- 看板按 (主体, 门店, 时间) 聚合，这三列一起走
CREATE INDEX idx_attr_log_store ON mkt_attribution_log (entity_no, store_no, at);
