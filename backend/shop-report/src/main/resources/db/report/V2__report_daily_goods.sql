-- 商品日汇总（TDD-B端报表库与日结 §2.2 ②，P2）。
--
-- 它回答的是「哪个商品卖得多 / 赚得多」，与进销存的动销榜**不是一张表**：
-- 那一张聚合 inv_ledger（件数与出库成本），这一张聚合 ord_item（件数与销售额）。
-- 两个域各答各的，见 TDD §1。
--
-- 数据来源：ord_item（goods_no / title / spec / qty / amount / category_no）
-- 连 ord_sub_order 取门店与成交状态。**售价本来就在交易域现成**，
-- 不需要往 inv_ledger 加列。

CREATE TABLE IF NOT EXISTS rpt_daily_goods
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    stat_date DATE NOT NULL COMMENT '统计日（按下单日归属，与 rpt_daily_store 同一条时间轴）',
    entity_no VARCHAR(64) NOT NULL COMMENT '商户主体。**查询必须带它** —— 本库无数据域拦截器',
    store_no VARCHAR(64) NOT NULL COMMENT '门店。合计在查询层 SUM，不落合计行',
    goods_no VARCHAR(64) NOT NULL COMMENT '商品号。**按商品汇总不按 SKU** —— SKU 维度是二期',

    -- 名字在**写入时快照**：报表库不 JOIN 商品表。
    -- 商品改名之后历史报表显示的仍是当时的名字，这是对的 —— 那张报表描述的是那一天
    title VARCHAR(255) DEFAULT NULL COMMENT '商品名（写入时快照）',
    spec VARCHAR(128) DEFAULT NULL COMMENT '规格（写入时快照）',
    category_no VARCHAR(64) DEFAULT NULL COMMENT '类目（写入时快照），按类目看销售时用',

    -- ⚠️ **qty 只数付费行**。赠品行价格为 0（买赠活动送的，见 OrderVO.isGift 的注释），
    -- 混进来的话「送出去 100 件」会被读成「卖了 100 件」—— 而那是静默失真：
    -- 数字看着很好，决策全错。所以赠品单独一列，每个数只说一件事。
    qty INT NOT NULL DEFAULT 0 COMMENT '卖出件数（**不含赠品**）',
    amount_minor BIGINT NOT NULL DEFAULT 0 COMMENT '销售额（分）= sum(ord_item.amount)',
    gift_qty INT NOT NULL DEFAULT 0 COMMENT '赠出件数（买赠活动送的，价格为 0）',

    -- ⚠️ **没有逐商品的退货列**。起草时写过 refund_qty / refund_amount_minor，
    -- 查下来 ord_after_sale 只有 sub_order_no 与一个总的 refund_minor，**没有行级信息** ——
    -- 「某个商品退了几件」从现有数据里推不出来。留两个恒为 0 的列比没有更坏：
    -- 它们看起来是「没退货」，而真相是「不知道」。
    -- 要做得先让售后单带上退的是哪几行，那是交易域的改动，不在本期。

    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    -- 日结先删后写，靠它保证重跑幂等
    UNIQUE KEY uk_rpt_daily_goods (stat_date, entity_no, store_no, goods_no),
    -- 查询形状固定是「某商户 + 某时间段，按销售额或件数排」
    KEY idx_rpt_daily_goods_entity (entity_no, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品日汇总（派生，可重算）';
