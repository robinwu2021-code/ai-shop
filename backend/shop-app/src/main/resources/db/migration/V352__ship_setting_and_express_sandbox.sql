-- 发货设置 + 快递测试模式（TDD-快递100商家寄件 §7）。
--
-- 发货设置挂门店：寄件地址、寄件人都是「从这家店发出去」的事实。全部可空 ——
-- 空 = 回落门店名 / 店主登录手机 / 门店地址（与改造前逐字相同），存量门店一行都不用补。

ALTER TABLE mch_store
    ADD COLUMN ship_sender_name VARCHAR(64) DEFAULT NULL COMMENT '寄件人。空=门店名',
    ADD COLUMN ship_sender_phone VARCHAR(32) DEFAULT NULL COMMENT '寄件电话。空=店主登录手机',
    ADD COLUMN ship_address VARCHAR(255) DEFAULT NULL COMMENT '寄件地址（带省市区的整条）。空=门店地址',
    ADD COLUMN ship_carrier VARCHAR(16) DEFAULT NULL COMMENT '默认快递公司，微信 delivery_id',
    ADD COLUMN ship_weight_g INT(11) DEFAULT NULL COMMENT '默认包裹重量（克）';

-- 取件单记下它是在哪个环境下的：取消要回到同一个环境；测试环境的运费不记商家欠款
ALTER TABLE ord_express_pickup
    ADD COLUMN sandbox TINYINT(4) NOT NULL DEFAULT 0 COMMENT '1=快递100 测试环境下的单（快递测试模式），不扣费、不记欠款';
