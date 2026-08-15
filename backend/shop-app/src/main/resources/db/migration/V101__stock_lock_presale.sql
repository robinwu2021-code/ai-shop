-- 锁定行要记住「这一笔吃的是现货还是预售额度」（TDD-运营端商品治理补齐 §4.1）。
--
-- **防住什么**：不记这一位，释放时就不知道该把数减回 prd_sku.locked_stock 还是 sold_count。
-- 减错的后果是「取消一单预售，现货库存凭空多出一件」—— 而那件货根本不存在，
-- 下一个买家会买到一件永远发不出去的商品，且要等到发货那天才发现。
--
-- 与既有的 store_no 同一个用法：锁在哪张表/哪个池子就记在哪，释放与确认据此决定回退方向。

ALTER TABLE prd_stock_lock
    ADD COLUMN presale TINYINT(4) NOT NULL DEFAULT 0 COMMENT '1=这一笔吃的是预售额度，释放时减 sold_count';
