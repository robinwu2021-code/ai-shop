-- 订单状态机 · 步 2：状态流水扩为「任意聚合」+ 状态列加宽
--
-- 1. ord_status_log 原来只挂子单（sub_order_no）。转换门要给支付单 / 订单 / 发货单 / 售后单都记流水，
--    每行写清是哪个聚合、哪一单、什么事件、从哪到哪。sub_order_no 保留为时间线锚点（老查询照旧）。
-- 2. 售后新状态 WAIT_OFFLINE_REFUND 有 19 个字符，ord_after_sale.status 与 ord_status_log.status 原为 16，放不下。
--
-- 纯加列 + 加宽，向后兼容。不写 ENGINE/CHARSET（见 prod-mysql97）。

ALTER TABLE ord_status_log
    ADD COLUMN aggregate_type VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT '聚合：PAYMENT / ORDER / SHIPMENT / AFTER_SALE。存量行都是订单流水',
    ADD COLUMN aggregate_no VARCHAR(64) DEFAULT NULL COMMENT '聚合单号（支付单号 / 子单号 / 发货单号 / 售后单号）。存量为空 = 同 sub_order_no',
    ADD COLUMN event VARCHAR(32) DEFAULT NULL COMMENT '状态机事件名（如 APPROVE / PAY_TIMEOUT）。存量为空',
    ADD COLUMN from_status VARCHAR(24) DEFAULT NULL COMMENT '迁移前状态。创建迁移与存量为空',
    MODIFY COLUMN status VARCHAR(24) NOT NULL;

ALTER TABLE ord_after_sale
    MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'APPLIED';
