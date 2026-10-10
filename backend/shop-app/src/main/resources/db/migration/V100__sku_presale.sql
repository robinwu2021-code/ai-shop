-- 预售额度与截单时间（矩阵 P-3.3.1 / 3.3.2，TDD-运营端商品治理补齐 §4.1）。
--
-- 四列都落在 prd_sku 上而不是新开一张 prd_sku_presale：预售额度与库存是**同一件事的两级**
-- （现货卖完了还能不能继续卖），拆两张表意味着下单那条原子扣减要跨表，
-- 而库存扣减是这个系统里最不该跨表的地方。
--
-- **presale_quota 默认 0 是关键**：0 = 不做预售，下单闸门原样只看现货。
-- 存量 SKU 因此一个字节的行为都不变 —— 否则这条迁移一上线，
-- 全平台商品的售罄判断会同时改变，而没有任何人配置过预售。
--
-- prd_sku 是「一市场一行」（唯一键 entity_no, sku_no, market），这四列在各市场行上重复。
-- 与库存同口径：**库存与预售额度都不分市场**（货就那么多，卖到哪个市场都是同一批），
-- 分市场的只有价格。所有写入按 sku_no 更新，与既有的 lockStock 逐字同构。

ALTER TABLE prd_sku
    ADD COLUMN presale_quota INT(11) NOT NULL DEFAULT 0 COMMENT '预售额度，0=不做预售',
    ADD COLUMN sold_count INT(11) NOT NULL DEFAULT 0 COMMENT '预售期内已售（锁定即计入，释放即回退）',
    ADD COLUMN cutoff_at DATETIME DEFAULT NULL COMMENT '截单时间，NULL=不设截单只靠额度封顶',
    ADD COLUMN arrive_at DATETIME DEFAULT NULL COMMENT '到货时间，截单必须早于它';
