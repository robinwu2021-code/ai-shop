-- 佣金归属快照：下单那一刻的承接方，写死在子订单上。
--
-- 现在「这一单算谁的」是运行时从 pickup_no 推出来的（自提点 → owner_ref）。
-- 自提点换了承接门店之后，**历史订单的归属会跟着变** —— 上个月的单
-- 突然算到新承接方头上，而钱早就结给旧的了。
--
-- 与同表的 pickup_name、receiver_* 是同一条理由（「改名/改地址不该影响历史订单」），
-- 只是那两列护的是显示，这一列护的是钱。
--
-- **现在加成本为零**（还没有真实订单）；等有了订单再补，那批订单的归属
-- 永远补不回来 —— 与 stl_payment.payer_openid 同理（V310）。
ALTER TABLE ord_sub_order
    ADD COLUMN pickup_owner_ref VARCHAR(32) NULL COMMENT '下单时自提点的承接方（owner_ref），佣金归属依据',
    ADD COLUMN pickup_owner_store_no VARCHAR(32) NULL COMMENT '下单时承接门店号（STORE 类型自提点）';
