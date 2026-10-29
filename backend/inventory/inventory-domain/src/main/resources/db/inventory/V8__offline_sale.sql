-- 线下卖出（TDD-商品纳入进销存开关 §5.2，第三期）。
--
-- 柜台把货卖掉了，账上也要减 —— 否则线上还按原来的实存放货，同一袋米会被再卖一次。
-- **不加表、不加列**：一次卖出就是一张出库单，撤销就是一张入库单，两张都是现成的结构。
-- 这里只把两个新的取值码写进列注释 —— 注释是这两列唯一的取值域说明，
-- 少写一个码，下一个读表的人就会以为它不存在。
--
-- OFFLINE_SALE 与 SALE 分开：线上销售挂订单号、可点进订单、进销售额；
-- 线下只有件数（出库单本来就不带售价）。混成一个码，销售报表就永远说不清
-- 那几件到底卖了多少钱。
ALTER TABLE inv_outbound_order
    MODIFY COLUMN purpose VARCHAR(16) NOT NULL
        COMMENT 'SALE 线上销售 / OFFLINE_SALE 线下卖出 / TRANSFER_OUT 调拨出 / SCRAP 报损 / COUNT_LOSS 盘亏 / INTERNAL 领用 / RETURN_SUPPLIER 退供应商 / OTHER';

ALTER TABLE inv_inbound_order
    MODIFY COLUMN source_type VARCHAR(16) NOT NULL
        COMMENT 'PURCHASE 采购 / RETURN 退货 / OFFLINE_RETURN 线下卖出撤销 / TRANSFER_IN 调拨入 / COUNT_GAIN 盘盈 / INIT 期初 / OTHER';
