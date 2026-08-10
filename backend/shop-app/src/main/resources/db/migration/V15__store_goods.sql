-- 门店级上架关系。
--
-- 为什么需要：`prd_goods` 只有 `entity_no`，两家店必然卖**同一批商品**。
-- 真实小店不是这样 —— 文三路店卖早点、古墩路店卖生鲜；开新店时先上十来样试水，
-- 而不是把总店的两百个 SKU 一次性铺开。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么是「上架关系」而不是「把商品复制一份到门店名下」
-- ─────────────────────────────────────────────────────────────────────────────
-- 复制会让改一次标题要改 N 遍，而且历史订单本来就固化了商品快照，
-- 不需要靠复制来保历史。同一款米就是同一款米 —— 商品定义留在主体级。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 这一批**不做分店价**
-- ─────────────────────────────────────────────────────────────────────────────
-- 方案里原本还有一个 `price_override`。没建，是刻意的：
-- 价格动的是**算价链路**（详情价 → 购物车 → 下单 → 营销叠加 → 订单快照），
-- 与「这家店卖不卖这件货」不是一个风险等级，要单独一批做。
--
-- 而先把列建出来放着不接，恰恰是这个仓库反复出问题的那个形状 ——
-- 有字段没有消费方（活动建了没人读、门店授权配了没人用、收款号存了没人读）。
-- 那种列不会报错，只会让下一个人以为分店价已经支持了。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 语义与门店库存（V13）逐字同款
-- ─────────────────────────────────────────────────────────────────────────────
--   · 某商品一条店级行都没有 → 走 prd_goods.on_sale，行为与今天完全相同
--   · 一旦有了任意一条 → 该商品整体转为店级管理，**没有行的店视为未上架**
--
-- 两处语义必须一致。不一致的症状很难查：库存说这家店有货、上架说这家店没这商品，
-- 页面显示与下单结果各说各话。
CREATE TABLE IF NOT EXISTS prd_store_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL COMMENT '门店业务键（mch_store.store_no）',
    goods_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '冗余主体号：按商家查全部门店上架情况时免 join',
    on_sale TINYINT(4) NOT NULL DEFAULT 0 COMMENT '这家店卖不卖这件货',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一店一商品一行
    UNIQUE KEY uk_store_goods (store_no,goods_no),
    KEY idx_sg_goods (goods_no),
    KEY idx_sg_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='门店级上架关系：有行则按店算，一条都没有则回退主体级 on_sale';
