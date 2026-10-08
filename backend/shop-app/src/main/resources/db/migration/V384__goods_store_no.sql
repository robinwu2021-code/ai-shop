-- 商品归属门店（ADR-030 / ADR-031，TDD-下单按门店拆单与运费模板 期 A）。
--
-- 商品只属于一家门店。此前商品挂主体、门店靠 prd_store_goods 投影现算，
-- 于是同主体两家店各卖各的货放进一单时，整个主体只能落一家店，总有一件被落到不卖它的店。
--
-- 这一期只加列 + 回填 + 建品写归属，读路径不变（期 B 才切）。先可空：
-- 线上 4 件多店在架的商品要在分店走正常建品接口各复制一件（进销存按 sku_no 建货品，
-- 迁移里克隆 SKU 会在进销存投影出空货品），复制完、回读 0 空行后再置 NOT NULL。
--
-- 回填优先级（与 TDD AC2 一致）：
--   1. 有门店行：在架且未被平台下架的店优先，其中默认店优先，再按建店先后、店号定序；
--   2. 没有门店行（单店商家的常态）：主体默认店，没有默认店取最早建的那家。
ALTER TABLE prd_goods ADD COLUMN store_no VARCHAR(32) DEFAULT NULL COMMENT '所属门店：商品只属于一家门店（ADR-030）';
CREATE INDEX idx_goods_store ON prd_goods (store_no, on_sale);

UPDATE prd_goods g SET g.store_no = COALESCE(
    (SELECT sg.store_no FROM prd_store_goods sg JOIN mch_store s ON s.store_no = sg.store_no
      WHERE sg.goods_no = g.goods_no AND sg.deleted = 0 AND s.deleted = 0
      ORDER BY (sg.on_sale = 1 AND sg.platform_suspended = 0) DESC, s.is_default DESC, s.created_at, s.store_no
      LIMIT 1),
    (SELECT s.store_no FROM mch_store s
      WHERE s.entity_no = g.entity_no AND s.deleted = 0
      ORDER BY s.is_default DESC, s.created_at, s.store_no
      LIMIT 1))
WHERE g.store_no IS NULL;
