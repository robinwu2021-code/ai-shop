-- 第 5 步 · 5.3：分账按退款金额回退（结算口径 A，TDD-订单状态机 §2C）
--
-- 1. stl_bill：已退货款累计（口径 A 的比例分子，也是每笔退款的 CAS 前值）、已经通道回退的商家所得累计。
-- 2. stl_bill_refund：一张售后单在结算单上扣了 / 回退了多少；after_sale_no 唯一 = 重放只落一次（集群规则 R3）。
--
-- 加列 + 建表，向后兼容（旧代码不读不写），可重复执行。不写 ENGINE/CHARSET（见 prod-mysql97）。

ALTER TABLE stl_bill
    ADD COLUMN refunded_goods_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '已退货款累计（买家实付口径，不含运费）',
    ADD COLUMN reversed_minor       BIGINT(20) NOT NULL DEFAULT 0 COMMENT '已经通道按额回退的商家所得累计';

CREATE TABLE IF NOT EXISTS stl_bill_refund (
    id                     BIGINT(20)  NOT NULL AUTO_INCREMENT,
    settle_no              VARCHAR(40) NOT NULL COMMENT '结算单号',
    sub_order_no           VARCHAR(40) NOT NULL COMMENT '子单号',
    after_sale_no          VARCHAR(40) NOT NULL COMMENT '售后单号（幂等键）',
    goods_minor            BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '本次退的货款',
    freight_minor          BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '本次退的运费',
    merchant_reverse_minor BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '商家所得回退额（分账后 = 发给通道的金额）',
    subsidy_return_minor   BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '积分补差收回额',
    platform_cut_minor     BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '平台少收（佣金 + 服务费 + 积分服务费）',
    pre_split              TINYINT(4)  NOT NULL DEFAULT 0 COMMENT '1 = 分账前（只扣金额）；0 = 分账后（经通道回退）',
    final_refund           TINYINT(4)  NOT NULL DEFAULT 0 COMMENT '1 = 本次之后货款已全部退完',
    at                     BIGINT(20)  NOT NULL COMMENT '发生时刻（毫秒）',
    tenant_no              VARCHAR(32) DEFAULT NULL,
    created_at             DATETIME    DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_bill_refund (after_sale_no),
    KEY idx_stl_bill_refund_settle (settle_no)
) COMMENT='结算单退款流水：一张售后单在结算单上扣了 / 回退了多少';
