-- 出库去向：这批货**出给谁**。
--
-- 此前出库单只答得出「为什么出」（reason_code：坏了 / 过期 / 送人 / 其它），
-- 答不出「出给谁」—— 端上把 purpose 写死成 SCRAP，于是所有非销售出库都是报损。
-- 退给供应商这件事因此记不了，而「这个月退给老周多少货」是应付账款对账的一半。
--
-- ── 为什么是两列而不是塞进 reason_code ────────────────────────────────────
--
-- reason_code 是「为什么」，去向是「给谁」，两个维度。挤进同一列之后：
--   · 报表再也分不开「这个月报损多少」与「这个月退货多少」——
--     而那恰恰是月底商家唯一想知道的两个数；
--   · 已有的四个原因值（BROKEN/EXPIRED/GIFT/OTHER）会与去向值混在一个取值域里，
--     谁也说不清 GIFT 是原因还是去向。
--
-- ── 为什么冗余存一个名字 ──────────────────────────────────────────────────
--
-- 与 V3 给供应商、V4 给承运方留冗余名字同一条规矩：**单据要能自证**。
-- 供应商三个月后改了名，历史退货单上该显示当时那个名字，而不是跟着变。
-- 跨库也不能外键（进销存是独立库），所以名字只能冗余。
--
-- ── SALE 不在这里 ────────────────────────────────────────────────────────
--
-- 「发货给客户」**不做成出库单上的一个新去向**。销售出库只能由预留 commit 产生
-- （OutboundServiceImpl 的闸门），允许手工建的话商家能凭空造一笔销售出库，
-- 而它会进销量榜。那道闸门这一轮一个字都不动。

ALTER TABLE inv_outbound_order
    ADD COLUMN target_type VARCHAR(16) DEFAULT NULL COMMENT '去向类型：SUPPLIER 退供应商 / STORE 门店领用。空 = 无去向（报损就是没有去向的）' AFTER purpose,
    ADD COLUMN target_no   VARCHAR(32) DEFAULT NULL COMMENT '去向对象编号：SUPPLIER 时是 inv_supplier.supplier_no' AFTER target_type,
    ADD COLUMN target_name VARCHAR(64) DEFAULT NULL COMMENT '下单当时的去向名字快照。**冗余是有意的** —— 对方改了名，历史单据仍显示当时那个名字' AFTER target_no;

-- 按去向查：「这个月退给老周多少货」走的就是这条。
-- owner_id 打头 —— 进销存不走平台 DataScope，每个查询都显式带它，索引也从它起。
CREATE INDEX idx_outbound_target ON inv_outbound_order (owner_id, target_type, target_no);
