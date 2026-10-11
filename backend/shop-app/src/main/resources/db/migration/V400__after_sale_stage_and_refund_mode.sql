-- 售后规则：履约进度 + 审核 + 部分退款与退款形式（TDD-售后规则-审核与部分退款 §2.5）。
--
-- 为什么加 prepared_at：录运单号那一刻子单就置 FULFILLING，所以「已发货」既可能是
-- 「刚录单号、货还在仓里」也可能是「商家已为生鲜采购甚至送出、还没录单号」时的 WAIT_FULFILL。
-- 能不能退取决于货还拦不拦得住，需要「商家开始备货」这个显式信号补上中间那段（FulfillStage）。

ALTER TABLE ord_sub_order
    ADD COLUMN prepared_at BIGINT NULL COMMENT '商家开始备货时刻(ms)；未备货为空。补 FulfillStage 的 PREPARING 档';

ALTER TABLE ord_after_sale
    ADD COLUMN refund_mode    VARCHAR(8) NOT NULL DEFAULT 'CASH' COMMENT '退款形式：CASH 原路退现金 / POINTS 折算积分补偿（商家审核时定）',
    ADD COLUMN refund_points  BIGINT     NULL COMMENT '退款形式为 POINTS 时发放的积分数（= 核定额 × PointsConfig.perMinor）',
    ADD COLUMN approved_minor BIGINT     NULL COMMENT '商家核定的退款额(分)，与买家申请额 refund_minor 分开留痕；仅已签收可改',
    ADD COLUMN fulfill_stage  VARCHAR(16) NULL COMMENT '申请当时的履约进度快照：NOT_PREPARED/PREPARING/LABELED/SHIPPED/RECEIVED';
