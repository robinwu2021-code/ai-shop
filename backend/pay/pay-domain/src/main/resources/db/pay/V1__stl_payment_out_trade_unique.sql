-- F07 · Review R07：stl_payment.out_trade_no 无唯一键，open() 的存在性检查又不加锁 ——
-- 并发两次「去支付」插出两行同号记录，之后按单号锁行（LIMIT 1）锁哪行不确定。
-- 商户单号本就该全表唯一：收款「订单号 / 订单号-N」，退款「原单号-RN」，从不复用。
--
-- 上线前置（执行方案 Q1）：SELECT out_trade_no, COUNT(*) FROM stl_payment GROUP BY out_trade_no HAVING COUNT(*) > 1;
-- 结果必须为空。不为空先按执行方案处置重复行，否则这条迁移失败、应用起不来。
ALTER TABLE stl_payment ADD UNIQUE KEY uk_stl_payment_out_trade_no (out_trade_no);
