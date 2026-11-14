-- F08 · Review R08：退款构造通道上下文时子商户号恒为 null，服务商 / 子商户模式下退款找不到原交易。
-- 收款时把实际下单用的子商户号与收款商户号落到这一行，退款从原交易取（存量行为空，退款时回退到请求快照）。
-- 两条 ALTER 分开写：H2 不认一条 ALTER 里的多个 ADD（schema-test 由生成器转换，这里照顾人工在 H2 上复现）。
ALTER TABLE stl_payment ADD COLUMN sub_merchant_id VARCHAR(64) DEFAULT NULL COMMENT '收款时下单用的子商户号（DIRECT 路径）；退款按它找原交易';
ALTER TABLE stl_payment ADD COLUMN pay_merchant_no VARCHAR(64) DEFAULT NULL COMMENT '收款商户号业务键（DIRECT 路径）';
