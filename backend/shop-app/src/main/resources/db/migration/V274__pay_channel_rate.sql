-- 通道费率与结算属性。
--
-- 此前 `stl_bill.channel_fee_rate`（通道费率快照）这一列建出来了，
-- 而**没有任何地方能配这个费率** —— 快照永远从 0 抄一个 0。
--
-- 形状照抄 `stl_fee_rule`（V28）：按生效时间分版本，只增不改。
-- 就地改的话历史账对不上，而对不上要到月底才发现。两处费率用同一套心智，
-- 运营不用学两遍。

CREATE TABLE IF NOT EXISTS sys_pay_channel_rate
(
    id             BIGINT(20)   NOT NULL AUTO_INCREMENT,
    rate_no        VARCHAR(64)  NOT NULL COMMENT '规则单号',
    pay_channel    VARCHAR(16)  NOT NULL COMMENT 'WECHAT/ALIPAY，与 sys_pay_channel 同值域',
    -- 分档：同一通道下不同支付方式、不同主体形态的费率常常不同
    pay_method     VARCHAR(16)  NOT NULL DEFAULT '*' COMMENT 'JSAPI/APP/H5/NATIVE；* = 该通道全部',
    legal_form     VARCHAR(16)  NOT NULL DEFAULT '*' COMMENT 'MICRO/INDIVIDUAL/ENTERPRISE；* = 全部',
    rate_bp        INT(11)      NOT NULL DEFAULT 0 COMMENT '万分比。38 = 0.38%',
    min_fee_minor  BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '单笔最低手续费（分）。有的通道有保底，0 = 无',
    -- 用毫秒时间戳，与 stl_fee_rule 同一口径，免得比较时来回转
    effective_from BIGINT(20)   NOT NULL COMMENT '生效时刻（毫秒）；未来时间 = 预约生效',
    enabled        TINYINT(4)   NOT NULL DEFAULT 1,
    remark         VARCHAR(255) DEFAULT NULL COMMENT '为什么调这一次 —— 回查时这句话比数字更有用',
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    version        BIGINT(20)   NOT NULL DEFAULT 0,
    deleted        TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pcr_no (rate_no, tenant_no),
    -- 同一格同一时刻只能有一条，否则「取最新一条」会变成随机取
    UNIQUE KEY uk_pcr_slot (pay_channel, pay_method, legal_form, effective_from, tenant_no)
) COMMENT='通道费率：通道 × 支付方式 × 主体形态，按生效时间分版本';

-- 通道的结算属性。**只加这两列** —— KYC 材料清单不加：
-- 没有真通道时设计的字段多半是猜的，而猜错的字段会被后来的人当成事实。
ALTER TABLE sys_pay_channel
    ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '结算币种';
ALTER TABLE sys_pay_channel
    ADD COLUMN settle_cycle VARCHAR(16) NOT NULL DEFAULT 'T+1' COMMENT '通道结算周期，展示与对账预期用';
