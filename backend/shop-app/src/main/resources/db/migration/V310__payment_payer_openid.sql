-- 支付单记下「付款人在哪个小程序下的 openid」。
--
-- 微信「发货信息录入」上报时必填 payer.openid，而 openid 是**按 AppID 隔离**的：
-- 拿当前会话的 openid 去报一笔旧 AppID 下的订单，微信认不出这个人。
-- 我们 2026-09 刚换过一次小程序号（wxdb0513c549437ffe → wx3201250c28393850），
-- 将来还可能再换，所以两列一起存：openid 本身，以及它属于哪个 appid。
--
-- 现在加成本为零（还没有真实支付单）；等有了订单再补，那批订单永远补不回来 ——
-- 而补不回来的后果是那些钱结不出账（见 TDD-微信发货信息录入）。
ALTER TABLE stl_payment
    ADD COLUMN payer_openid VARCHAR(64) NULL COMMENT '付款人 openid（按 wx_appid 隔离）',
    ADD COLUMN wx_appid     VARCHAR(32) NULL COMMENT '支付发生时所用的小程序 AppID';
