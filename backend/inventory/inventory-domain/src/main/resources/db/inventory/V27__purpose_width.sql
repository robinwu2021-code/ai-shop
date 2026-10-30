-- 单据用途列放宽到 32（TDD-进销存独立服务与多业态 §2.20，评审 D1）。
--
-- 用途码由业态档案登记，档案校验允许 32 个字符；流水的快照列 inv_ledger.reason_code 在 V15 已经是 32。
-- 单据头这两列还停在 16，档案装得进去的用途建单时写不进去（报 500），等于业态差异被列宽卡住。
-- 注释沿用 V8 的取值说明，另注明业态档案可登记更多。
ALTER TABLE inv_outbound_order
    MODIFY COLUMN purpose VARCHAR(32) NOT NULL
        COMMENT 'SALE 线上销售 / OFFLINE_SALE 线下卖出 / TRANSFER_OUT 调拨出 / SCRAP 报损 / COUNT_LOSS 盘亏 / INTERNAL 领用 / RETURN_SUPPLIER 退供应商 / OTHER；宿主接口另按业态档案登记的用途';

ALTER TABLE inv_inbound_order
    MODIFY COLUMN source_type VARCHAR(32) NOT NULL
        COMMENT 'PURCHASE 采购 / RETURN 退货 / OFFLINE_RETURN 线下卖出撤销 / TRANSFER_IN 调拨入 / COUNT_GAIN 盘盈 / INIT 期初 / OTHER；宿主接口另按业态档案登记的用途';
