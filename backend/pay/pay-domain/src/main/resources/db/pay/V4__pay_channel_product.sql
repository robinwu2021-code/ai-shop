-- 支付服务 v4 · 迁移 4（开发计划 I2 · TDD-支付服务-I2渠道统一封装 S1）：支付产品与地区。
-- 产品 = 渠道 × 接入模式 × 支付类型 × 支付方式，是运营开关；实际能力以代码实现的处理器为准，启动比对（ChannelHandlerRegistry）。
-- sys_pay_channel 加 4 列；sys_pay_channel_market 加 status（默认 ENABLED）。
-- 偏差：v4 模型把 sys_pay_channel_market 的语义改为「无行 = 不可用」—— 本迁移**只加列不改语义**，
-- 现有「空集 = 不限市场」仍生效（F14 已确认），语义切换随新渠道上线（I8）。新渠道的渠道行同样留到 I8。

CREATE TABLE pay_channel_product (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  product_code VARCHAR(64) NOT NULL COMMENT '产品码（种子数据的自然键），如 NEWEBPAY_CREDIT、LINEPAY_ONLINE、PAYBY_POS_CARD',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码（值域，见 sys_pay_channel）',
  access_mode VARCHAR(24) NOT NULL COMMENT 'MERCHANT_OWN / SERVICE_PROVIDER / PLATFORM_SUB：用谁的凭据调渠道',
  pay_type VARCHAR(24) NOT NULL COMMENT 'ONLINE / OFFLINE / CARD：走哪类能力接口',
  pay_method VARCHAR(32) NOT NULL COMMENT '统一支付方式码：CARD_ONLINE / APPLE_PAY / ATM / CVS / LINE_PAY / TAIWAN_PAY / WALLET / JSAPI / SCAN_CUSTOMER / STORE_QR / CARD_PRESENT …',
  channel_method VARCHAR(32) NOT NULL COMMENT '渠道侧方式码：蓝新 CREDIT / VACC / CVS，微信 JSAPI，PayBy 产品码',
  allowed_funds_paths VARCHAR(32) NOT NULL COMMENT '逗号分隔：DIRECT / AGGREGATED；TW 两家只允许 DIRECT',
  refund_mode VARCHAR(24) NOT NULL COMMENT 'ONLINE 原路退 / OFFLINE 无原路退需线下 / UNSUPPORTED',
  partial_refund TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否支持部分退款',
  max_partial_refunds INT NOT NULL DEFAULT 0 COMMENT '单笔最多部分退款次数；0 = 未知 / 不限',
  refund_interval_seconds INT NOT NULL DEFAULT 0 COMMENT '两次退款最小间隔',
  refund_window_days INT NOT NULL DEFAULT 0 COMMENT '可退款天数；0 = 未知',
  two_phase TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否两段式（ATM / 超商取号后再缴费；LINE Pay 授权后 confirm）',
  status VARCHAR(24) NOT NULL COMMENT 'ENABLED / DISABLED',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '收银台排序',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_channel_product_1 (product_code),
  UNIQUE KEY uk_pay_channel_product_2 (pay_channel,access_mode,pay_type,pay_method),
  KEY idx_pay_channel_product_1 (pay_channel,status)
) COMMENT='支付产品：渠道 × 接入模式 × 支付类型 × 支付方式。运营开关；实际能力以代码实现的接口为准，启动比对';

CREATE TABLE pay_channel_product_market (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_channel_product_id BIGINT NOT NULL COMMENT '支付产品 pay_channel_product.id',
  market VARCHAR(8) NOT NULL COMMENT '市场码（值域，见 sys_market）',
  currency CHAR(3) NOT NULL COMMENT '该地区收款币种',
  single_min_minor BIGINT NOT NULL DEFAULT 0 COMMENT '单笔下限（最小单位）',
  single_max_minor BIGINT NOT NULL DEFAULT 0 COMMENT '单笔上限；0 = 不限',
  amount_scale TINYINT NOT NULL COMMENT '出通道金额精度：TWD 在蓝新 / LINE Pay 为 0（整数元），AED 为 2；有余数即拒',
  status VARCHAR(24) NOT NULL COMMENT 'ENABLED / DISABLED',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_channel_product_market_1 (pay_channel_product_id,market),
  KEY idx_pay_channel_product_market_1 (market,status)
) COMMENT='支付产品在哪些国家地区可用，及该地区的币种与金额范围。无行 = 该产品在该地区不可用';

ALTER TABLE sys_pay_channel ADD COLUMN callback_style VARCHAR(16) NOT NULL DEFAULT 'PATH' COMMENT 'PATH 子路径 / QUERY 参数：渠道是否接受带路径变量的回调地址';
ALTER TABLE sys_pay_channel ADD COLUMN sandbox_host VARCHAR(128) NULL COMMENT '沙箱主机（不含密钥）';
ALTER TABLE sys_pay_channel ADD COLUMN prod_host VARCHAR(128) NULL COMMENT '正式主机';
ALTER TABLE sys_pay_channel ADD COLUMN supports_statement TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否有对账单下载接口（无则退化逐笔查单）';
ALTER TABLE sys_pay_channel_market ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED。语义仍为「空集 = 不限市场」，无行即不可用的切换随新渠道上线（I8）';

-- 种子：CN 现有能力 + 测试渠道。微信直连是归集路径（钱进平台号），收付通是服务商路径（钱进子商户）。
-- 支付宝直付通 DISABLED：客户端未实现（I1 F12），开了也没有处理器 —— 启动比对会拦。
INSERT INTO pay_channel_product (product_code, pay_channel, access_mode, pay_type, pay_method, channel_method,
    allowed_funds_paths, refund_mode, partial_refund, max_partial_refunds, refund_interval_seconds, refund_window_days,
    two_phase, status, sort_no, created_at, updated_at) VALUES
('WECHAT_JSAPI_DIRECT', 'WECHAT', 'MERCHANT_OWN', 'ONLINE', 'WECHAT_PAY', 'JSAPI', 'AGGREGATED', 'ONLINE', 1, 50, 60, 365, 0, 'ENABLED', 10, NOW(3), NOW(3)),
('WECHAT_JSAPI_SP', 'WECHAT', 'SERVICE_PROVIDER', 'ONLINE', 'WECHAT_PAY', 'JSAPI', 'DIRECT', 'ONLINE', 1, 50, 60, 365, 0, 'ENABLED', 11, NOW(3), NOW(3)),
('ALIPAY_APP_SP', 'ALIPAY', 'SERVICE_PROVIDER', 'ONLINE', 'ALIPAY', 'APP', 'DIRECT', 'ONLINE', 1, 0, 0, 365, 0, 'DISABLED', 20, NOW(3), NOW(3)),
('TEST_ONLINE', 'TEST', 'PLATFORM_SUB', 'ONLINE', 'TEST_PAY', 'TEST_PAY', 'DIRECT,AGGREGATED', 'ONLINE', 1, 20, 0, 365, 0, 'ENABLED', 90, NOW(3), NOW(3));

INSERT INTO pay_channel_product_market (pay_channel_product_id, market, currency, single_min_minor, single_max_minor,
    amount_scale, status, created_at, updated_at)
SELECT p.id, 'CN', 'CNY', 1, 0, 2, 'ENABLED', NOW(3), NOW(3) FROM pay_channel_product p
 WHERE p.product_code IN ('WECHAT_JSAPI_DIRECT', 'WECHAT_JSAPI_SP', 'ALIPAY_APP_SP', 'TEST_ONLINE')
   AND NOT EXISTS (SELECT 1 FROM pay_channel_product_market x WHERE x.pay_channel_product_id = p.id AND x.market = 'CN');
