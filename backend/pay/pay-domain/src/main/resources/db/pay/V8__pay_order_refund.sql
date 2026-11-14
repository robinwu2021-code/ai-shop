-- 支付服务 v4 · 迁移 8（I3 · S1）：支付单、退款单、复核规则、两人复核、支付异常、收银会话；
-- stl_payment 挂到支付单上（7 列 + 2 索引；sub_merchant_id 已在 V2），stl_bill 加市场与来源支付单。

CREATE TABLE pay_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_order_no VARCHAR(64) NOT NULL COMMENT '支付单号（对外：接口响应、收银台、对账）',
  pay_app_id BIGINT NOT NULL COMMENT '调用方 pay_app.id（内部系统也注册 app，如 MALL / FOOD）',
  out_order_no VARCHAR(64) NOT NULL COMMENT '调用方订单号；与 pay_app_id 联合唯一（幂等）',
  biz_type VARCHAR(24) NOT NULL COMMENT 'MALL_ORDER / FOOD_CHECK / STORED_VALUE / PARTNER / COLLECT …',
  biz_no VARCHAR(64) NOT NULL COMMENT '调用方业务单号（字符串，由调用方给出，不作关联）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  entity_name_snapshot VARCHAR(128) NOT NULL COMMENT '成交时主体名',
  store_name_snapshot VARCHAR(128) NOT NULL COMMENT '成交时门店名',
  market VARCHAR(8) NOT NULL COMMENT '市场码',
  currency CHAR(3) NOT NULL COMMENT '币种',
  amount_minor BIGINT NOT NULL COMMENT '应付金额（最小单位），建单后不改',
  paid_minor BIGINT NOT NULL DEFAULT 0 COMMENT '通道实收',
  refunded_minor BIGINT NOT NULL DEFAULT 0 COMMENT '已退',
  reserved_refund_minor BIGINT NOT NULL DEFAULT 0 COMMENT '退款复核 / 在途占用',
  subject VARCHAR(128) NOT NULL COMMENT '商品标题（展示在通道收银台）',
  scene VARCHAR(24) NOT NULL COMMENT 'ONLINE / IN_STORE',
  pay_type VARCHAR(24) NULL COMMENT '选定方式后写入：ONLINE / OFFLINE / CARD',
  pay_method VARCHAR(32) NULL COMMENT '选定的统一支付方式码',
  pay_channel_product_id BIGINT NULL COMMENT '选定的支付产品 pay_channel_product.id',
  pay_merchant_id BIGINT NULL COMMENT '**路由快照**：收款商户 pay_merchant.id（选定方式时冻结，此后不改）',
  pay_channel VARCHAR(32) NULL COMMENT '路由快照：渠道码',
  access_mode VARCHAR(24) NULL COMMENT '路由快照：接入模式',
  funds_path VARCHAR(24) NULL COMMENT '路由快照：资金路径',
  sub_merchant_id VARCHAR(64) NULL COMMENT '路由快照：渠道子商户号（渠道侧字符串）',
  sec_key_version INT NULL COMMENT '路由快照：密钥版本号',
  status VARCHAR(24) NOT NULL COMMENT 'CREATED / AWAITING_PAYMENT / AUTHORIZED / AWAITING_CONFIRM / SUCCEEDED / PARTIALLY_REFUNDED / REFUNDED / CLOSED / FAILED',
  notify_url VARCHAR(512) NULL COMMENT '第三方 webhook 覆盖地址（白名单内）',
  return_url VARCHAR(512) NULL COMMENT '付款后跳回（白名单内）',
  source_type VARCHAR(24) NOT NULL COMMENT 'PARTNER / INTERNAL / TERMINAL / BIZ / OPS / CHECKOUT',
  source_id BIGINT NOT NULL COMMENT '来源 id：PARTNER / INTERNAL → pay_app.id；TERMINAL → pay_terminal.id；CHECKOUT → pay_qr_code.id 或 pay_checkout_session.id；BIZ / OPS → 账号 id',
  operator_no VARCHAR(64) NULL COMMENT '收银员 / 商家操作员（审计字段）',
  user_id BIGINT NULL COMMENT '顾客 usr_account.id（已登录时）',
  request_sha256 CHAR(64) NOT NULL COMMENT '建单请求规范化摘要：同幂等键不同内容 → 拒绝',
  expire_at DATETIME(3) NOT NULL COMMENT '支付截止',
  succeeded_at DATETIME(3) NULL COMMENT '成功时间',
  closed_at DATETIME(3) NULL COMMENT '关单时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_order_1 (pay_order_no),
  UNIQUE KEY uk_pay_order_2 (pay_app_id,out_order_no),
  KEY idx_pay_order_1 (biz_type,biz_no),
  KEY idx_pay_order_2 (store_id,created_at),
  KEY idx_pay_order_3 (status,expire_at),
  KEY idx_pay_order_4 (pay_merchant_id,succeeded_at),
  KEY idx_pay_order_5 (user_id,created_at),
  CONSTRAINT ck_pay_order_1 CHECK (paid_minor >= 0 AND refunded_minor >= 0 AND reserved_refund_minor >= 0),
  CONSTRAINT ck_pay_order_2 CHECK (refunded_minor + reserved_refund_minor <= paid_minor)
) COMMENT='支付订单：调用方要收的一笔钱，独立于业务订单。一张支付单可有多次通道尝试（stl_payment）';

CREATE TABLE pay_refund (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_refund_no VARCHAR(64) NOT NULL COMMENT '退款单号（对外）',
  pay_order_id BIGINT NOT NULL COMMENT '原支付单 pay_order.id（路由一律取原单快照；本表无路由列）',
  pay_app_id BIGINT NOT NULL COMMENT '发起方 pay_app.id',
  out_refund_no VARCHAR(64) NOT NULL COMMENT '发起方退款单号（幂等）',
  biz_refund_no VARCHAR(64) NULL COMMENT '业务售后单号（字符串，取代合成的 AS-{subOrderNo}，R21）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  currency CHAR(3) NOT NULL COMMENT '币种（= 原单）',
  amount_minor BIGINT NOT NULL COMMENT '退款金额',
  refund_mode VARCHAR(24) NOT NULL COMMENT 'ONLINE 原路 / OFFLINE 线下（商家自退并上传凭证）',
  reason VARCHAR(255) NOT NULL COMMENT '退款原因',
  status VARCHAR(24) NOT NULL COMMENT 'REQUESTED / PENDING_REVIEW / PENDING_SECOND_REVIEW / APPROVED / DISPATCHED / SUCCEEDED / FAILED / REJECTED / OFFLINE_PENDING_PROOF',
  requested_by VARCHAR(64) NOT NULL COMMENT '发起人（审计字段）',
  authorized_by VARCHAR(64) NULL COMMENT '店长授权人（终端发起时，审计字段）',
  pay_refund_review_rule_id BIGINT NULL COMMENT '命中的复核规则版本 pay_refund_review_rule.id（一行即一个版本）',
  hit_reasons VARCHAR(255) NULL COMMENT '命中原因：SINGLE_AMOUNT / DAILY_AMOUNT / RATIO / AFTER_DAYS / ALWAYS / NO_ACTIVE_RULE',
  pay_approval_id BIGINT NULL COMMENT '复核单 pay_approval.id',
  stl_payment_id BIGINT NULL COMMENT '执行时生成的通道交易 stl_payment.id',
  offline_proof_ref VARCHAR(255) NULL COMMENT '线下退款凭证文件引用',
  source_type VARCHAR(24) NOT NULL COMMENT 'BIZ / TERMINAL / PARTNER',
  source_id BIGINT NOT NULL COMMENT '来源 id（同 pay_order.source_id）',
  failed_reason VARCHAR(255) NULL COMMENT '失败原因（原样告知商家）',
  succeeded_at DATETIME(3) NULL COMMENT '成功时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_refund_1 (pay_refund_no),
  UNIQUE KEY uk_pay_refund_2 (pay_app_id,out_refund_no),
  KEY idx_pay_refund_1 (pay_order_id),
  KEY idx_pay_refund_2 (status,created_at),
  KEY idx_pay_refund_3 (store_id,created_at),
  KEY idx_pay_refund_4 (biz_refund_no),
  CONSTRAINT ck_pay_refund_1 CHECK (amount_minor > 0)
) COMMENT='退款单：商家侧发起的一次退款申请，含复核。执行由 RefundExecutionService 落 stl_payment（direction=REFUND）';

CREATE TABLE pay_refund_review_rule (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  rule_code VARCHAR(64) NOT NULL COMMENT '规则码：同一规则的各版本共用',
  version_index INT NOT NULL COMMENT '版本',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT / ACTIVE / RETIRED',
  active_key VARCHAR(64) NULL COMMENT 'ACTIVE 时 = rule_code：一条规则同一时刻一个生效版本',
  market VARCHAR(8) NOT NULL COMMENT '市场（必填）',
  currency CHAR(3) NOT NULL COMMENT '币种（必填）',
  store_id BIGINT NULL COMMENT '门店 mch_store.id；NULL = 全市场（门店级更具体者优先）',
  source_type VARCHAR(24) NOT NULL DEFAULT '' COMMENT '空串 = 全来源',
  pay_method VARCHAR(32) NOT NULL DEFAULT '' COMMENT '空串 = 全方式',
  single_amount_minor BIGINT NULL COMMENT '单笔 ≥ 此值',
  daily_amount_minor BIGINT NULL COMMENT '门店当日（市场时区）累计 + 本笔 ≥ 此值',
  refund_ratio_bp INT NULL COMMENT '本笔占原单比例 ≥ 此值（万分比）',
  after_days INT NULL COMMENT '原单支付超过 N 天',
  always_review TINYINT(1) NOT NULL DEFAULT 0 COMMENT '强制全部复核',
  review_timeout_minutes INT NOT NULL DEFAULT 1440 COMMENT '超时提醒（不自动通过）',
  drafted_by VARCHAR(64) NOT NULL COMMENT '起草人（审计字段）',
  activated_by VARCHAR(64) NULL COMMENT '生效确认人，必须 ≠ 起草人',
  activated_at DATETIME(3) NULL COMMENT '生效时间',
  retired_at DATETIME(3) NULL COMMENT '退役时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_refund_review_rule_1 (rule_code,version_index),
  UNIQUE KEY uk_pay_refund_review_rule_2 (active_key),
  KEY idx_pay_refund_review_rule_1 (market,currency,status),
  CONSTRAINT ck_pay_refund_review_rule_1 CHECK (status <> 'ACTIVE' OR always_review = 1 OR single_amount_minor IS NOT NULL OR daily_amount_minor IS NOT NULL OR refund_ratio_bp IS NOT NULL OR after_days IS NOT NULL),
  CONSTRAINT ck_pay_refund_review_rule_2 CHECK (activated_by IS NULL OR activated_by <> drafted_by)
) COMMENT='退款复核规则（一行一个版本）：命中任一门槛 → 平台两人复核。某市场无 ACTIVE 规则 = 该市场全部复核';

CREATE TABLE pay_approval (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  subject_type VARCHAR(24) NOT NULL COMMENT 'REFUND / PAYOUT / KEY_ENABLE / FUNDS_PATH_SWITCH / MERCHANT_ACTIVATE / REVIEW_RULE_ACTIVATE',
  subject_id BIGINT NOT NULL COMMENT '对象 id（按 subject_type：pay_refund / stl_payout / sec_key_version / pay_merchant / pay_refund_review_rule）',
  subject_sha256 CHAR(64) NOT NULL COMMENT '提交复核时对象内容摘要；复核时对象被改动即失效',
  amount_minor BIGINT NULL COMMENT '涉及金额',
  currency CHAR(3) NULL COMMENT '币种',
  requested_by VARCHAR(64) NOT NULL COMMENT '申请人',
  status VARCHAR(24) NOT NULL COMMENT 'PENDING_FIRST / PENDING_SECOND / APPROVED / REJECTED / CANCELLED / EXPIRED',
  open_key VARCHAR(64) NULL COMMENT '进行中时 = subject_type|subject_id：一个对象同时只有一张进行中复核单',
  first_reviewer VARCHAR(64) NULL COMMENT '第一复核人',
  first_decided_at DATETIME(3) NULL COMMENT '时间',
  second_reviewer VARCHAR(64) NULL COMMENT '第二复核人',
  second_decided_at DATETIME(3) NULL COMMENT '时间',
  reject_reason VARCHAR(255) NULL COMMENT '驳回原因',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_approval_1 (open_key),
  KEY idx_pay_approval_1 (status,created_at),
  KEY idx_pay_approval_2 (subject_type,subject_id),
  CONSTRAINT ck_pay_approval_1 CHECK (first_reviewer IS NULL OR first_reviewer <> requested_by),
  CONSTRAINT ck_pay_approval_2 CHECK (second_reviewer IS NULL OR (second_reviewer <> requested_by AND second_reviewer <> first_reviewer))
) COMMENT='两人复核单（通用）：退款、出款、密钥启用、资金路径切换、收款商户启用、复核规则生效';

CREATE TABLE pay_exception (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  kind VARCHAR(24) NOT NULL COMMENT 'LATE_PAYMENT / AMOUNT_MISMATCH / DUPLICATE_PAYMENT / CURRENCY_MISMATCH',
  pay_order_id BIGINT NOT NULL COMMENT '支付单 pay_order.id',
  stl_payment_id BIGINT NOT NULL COMMENT '通道尝试 stl_payment.id',
  amount_minor BIGINT NOT NULL COMMENT '涉及金额',
  currency CHAR(3) NOT NULL COMMENT '币种',
  status VARCHAR(24) NOT NULL COMMENT 'OPEN / AUTO_REFUNDING / RESOLVED / IGNORED',
  resolution VARCHAR(255) NULL COMMENT '处置结论（只放结论）',
  note VARCHAR(512) NULL COMMENT '说明',
  handled_by VARCHAR(64) NULL COMMENT '处置人',
  handled_at DATETIME(3) NULL COMMENT '处置时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_exception_1 (kind,stl_payment_id),
  KEY idx_pay_exception_1 (status,created_at),
  KEY idx_pay_exception_2 (pay_order_id)
) COMMENT='支付异常：关单后支付、金额不符、重复支付等需要处置的事实（修 R18：关单后成功不再静默改成功）';

CREATE TABLE pay_checkout_session (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  token_sha256 CHAR(64) NOT NULL COMMENT '令牌哈希；明文只在链接里',
  pay_order_id BIGINT NOT NULL COMMENT '支付单 pay_order.id',
  status VARCHAR(24) NOT NULL COMMENT 'OPEN / PAYING / DONE / EXPIRED / REVOKED',
  expire_at DATETIME(3) NOT NULL COMMENT '过期（≤ 支付单过期）',
  user_id BIGINT NULL COMMENT '顾客 usr_account.id（登录后回填）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_checkout_session_1 (token_sha256),
  KEY idx_pay_checkout_session_1 (pay_order_id)
) COMMENT='收银会话：扫码 H5 / 托管收银台的一次性令牌，绑定一张支付单';

ALTER TABLE stl_payment ADD COLUMN pay_order_id BIGINT NULL COMMENT '所属支付单 pay_order.id（回填后对新数据必填）';
ALTER TABLE stl_payment ADD COLUMN pay_refund_id BIGINT NULL COMMENT 'REFUND 方向：所属退款单 pay_refund.id';
ALTER TABLE stl_payment ADD COLUMN pay_merchant_id BIGINT NULL COMMENT '冻结的收款商户 pay_merchant.id（修 R08）';
ALTER TABLE stl_payment ADD COLUMN funds_path VARCHAR(24) NULL COMMENT '冻结的资金路径';
ALTER TABLE stl_payment ADD COLUMN market VARCHAR(8) NULL COMMENT '市场';
ALTER TABLE stl_payment ADD COLUMN sec_key_version INT NULL COMMENT '冻结的密钥版本号';
ALTER TABLE stl_payment ADD COLUMN channel_amount_minor BIGINT NULL COMMENT '通道回执金额；不再覆盖 amount_minor（修 R20）';
ALTER TABLE stl_payment ADD COLUMN channel_currency CHAR(3) NULL COMMENT '通道回执币种';
CREATE INDEX idx_stl_payment_pay_order ON stl_payment (pay_order_id);
CREATE INDEX idx_stl_payment_pay_merchant ON stl_payment (pay_merchant_id, succeeded_at);

ALTER TABLE stl_bill ADD COLUMN market VARCHAR(8) NULL COMMENT '市场';
ALTER TABLE stl_bill ADD COLUMN pay_order_id BIGINT NULL COMMENT '来源支付单 pay_order.id';

-- 各开放市场一条 DRAFT 复核规则：起草不生效。**某市场没有 ACTIVE 规则 = 该市场退款全部复核**，
-- 所以生效前的默认是最严的；运营按两人规则（起草人 ≠ 生效人）确认门槛后才放宽。
INSERT INTO pay_refund_review_rule (rule_code, version_index, status, market, currency, source_type, pay_method,
    single_amount_minor, daily_amount_minor, refund_ratio_bp, after_days, always_review, review_timeout_minutes,
    drafted_by, created_at, updated_at) VALUES
('DEFAULT_CN', 1, 'DRAFT', 'CN', 'CNY', '', '', 100000, 500000, 10000, 90, 0, 1440, 'SYSTEM', NOW(3), NOW(3));
