-- 原始基数与双退款位置分离；实际已付未知时不补零。
ALTER TABLE stl_bill
    ADD COLUMN refunded_freight_minor BIGINT NOT NULL DEFAULT 0 COMMENT '累计已退运费（分）',
    ADD COLUMN merchant_paid_minor BIGINT NULL COMMENT '回执确认商家实际已付（分）；null待核对';
ALTER TABLE stl_bill_refund
    ADD COLUMN merchant_goods_minor BIGINT NULL COMMENT '本次商家货款所得分配（分）',
    ADD COLUMN gross_cut_minor BIGINT NULL COMMENT '本次原始结算基数扣减（分）',
    ADD COLUMN commission_cut_minor BIGINT NULL COMMENT '本次佣金扣减（分）',
    ADD COLUMN service_fee_cut_minor BIGINT NULL COMMENT '本次履约服务费扣减（分）',
    ADD COLUMN points_fee_cut_minor BIGINT NULL COMMENT '本次积分服务费扣减（分）',
    ADD COLUMN points_cost_cut_minor BIGINT NULL COMMENT '本次积分成本扣减（分）',
    ADD COLUMN freight_reverse_minor BIGINT NULL COMMENT '本次商家运费回退（分）',
    ADD COLUMN cash_allocation VARCHAR(24) NULL COMMENT '现金分配NONE/PROPORTIONAL';
ALTER TABLE stl_payment
    ADD COLUMN refund_goods_minor BIGINT NULL COMMENT '原路退款货款现金（分）；历史未知null',
    ADD COLUMN refund_freight_minor BIGINT NULL COMMENT '原路退款运费现金（分）；历史未知null';
