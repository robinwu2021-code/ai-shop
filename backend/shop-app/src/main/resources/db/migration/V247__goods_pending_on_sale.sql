-- 重审期间记住「他本来要卖它」。
--
-- 过审之后商家还要再点一次「上架」，两个后果：新品提交完他以为在卖了其实一件卖不出；
-- 更常见的是**改一个在售商品的错别字，它就永远下架了** —— 保存把 on_sale 置 false
-- 送去重审，过审后没人把它放回去，而列表里写着「已过审」看不出还差一步。
--
-- 为什么是记意向而不是「过审即置真」：**「过审 ≠ 上架」是既有设计且有测试锁着**
-- （M9aOpsFlowTest.goodsAuditQueueOnlyPending 断言过审后 OFF_SALE），
-- 而无条件置真还会打破多门店的货架语义 —— 主体级 on_sale 一开，
-- 没有店级行的门店会跟着一起在架，正好与商家刚做的事相反
-- （StoreGoodsFlowTest.storeWithoutRowIsNotOnSale）。
-- 所以只恢复**他真的表达过**的那一份：提交审核时置 1，保存时继承原来的在架状态。
ALTER TABLE prd_goods
    ADD COLUMN IF NOT EXISTS pending_on_sale TINYINT NOT NULL DEFAULT 0
        COMMENT '重审期间记住的上架意向：过审时用它恢复 on_sale，用完清零';
