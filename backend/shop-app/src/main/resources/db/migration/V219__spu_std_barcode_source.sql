-- 标准品补两列：条码与来源。
--
-- ── 为什么要条码 ──
--
-- 标准品要回答的是「这是不是同一件货」，而**条码是这个问题最硬的答案** ——
-- 比标题聚类可靠得多：「农夫山泉饮用天然水 550ml」在三家店里可能写成三种样子，
-- 但 6921168509256 只有一个。有了它，扫码建品、跨店比价、重复标准品合并
-- 才有一个不依赖文案的判据。
--
-- 允许为空：散装菜、现做熟食、服务类标准品**天然没有条码**，
-- 而它们恰恰是这个平台的主力货。把条码做成必填等于把这些货挡在标准品之外。
--
-- ── 为什么要来源 ──
--
-- 这一批种子来自 Open Food Facts（ODbL 开放数据），而 ODbL 带两项义务：
-- 署名，以及衍生数据库的同权分享。不记来源的话，**几个月后没有人分得清
-- 哪些条目需要标注出处** —— 那时候要么全量重查，要么整库按最严的口径处理。
--
-- 取值：OFF（Open Food Facts）/ OPS（运营手工建）/ MERCHANT（商家沉淀）。
-- 默认 OPS：存量那 14 条都是运营手工建的。
ALTER TABLE prd_spu_std
    ADD COLUMN barcode VARCHAR(32) DEFAULT NULL COMMENT '商品条码（GS1）。散装/现做/服务类天然没有，允许为空',
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'OPS' COMMENT '来源：OPS 运营手工 / OFF Open Food Facts(ODbL，需署名) / MERCHANT 自家商品沉淀';

-- 条码唯一但允许多个 NULL —— MySQL 的唯一索引对 NULL 不去重，正合此处：
-- 有条码的不许重复（重复就是同一件货被建了两遍，比价会把它算成两个）；
-- 没条码的（散装、服务）不受这条约束。
CREATE UNIQUE INDEX uk_spu_std_barcode ON prd_spu_std (tenant_no, barcode);
