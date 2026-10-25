-- DESIGN ONLY. DO NOT APPLY AS A FLYWAY MIGRATION.
-- 支付服务数据库设计 v4 · 评审 DDL（由 pay-service-database.json 生成，勿手改）
-- 无 tenant_no；主键 id BIGINT AUTO_INCREMENT（Java Long）；关联一律 *_id BIGINT；核心系统按 store_id 关联。
-- MariaDB 12（生产）；落迁移时须过 node scripts/check-sql-portability.mjs（H2 测试库）。
-- 表尾不写 ENGINE/CHARSET/COLLATE，跟随库默认（与老表 join 不冲突，V284 教训）。
-- 不使用 IF NOT EXISTS 掩盖结构漂移；迁移号实施时盘点后再定，本文件不预占。


-- ════════ 渠道与地区 ════════

-- pay-channel · 支付产品：渠道 × 接入模式 × 支付类型 × 支付方式。运营开关；实际能力以代码实现的接口为准，启动比对
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

-- pay-channel · 支付产品在哪些国家地区可用，及该地区的币种与金额范围。无行 = 该产品在该地区不可用
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


-- ════════ 进件与收款商户 ════════

-- pay-domain/onboarding · 进件单：某门店向某渠道申请收款能力。资料与每次提交是版本；成功产出 pay_merchant
CREATE TABLE pay_onboarding (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  onboarding_no VARCHAR(64) NOT NULL COMMENT '进件单号（对外：进件回调地址里使用）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码',
  market VARCHAR(8) NOT NULL COMMENT '市场码',
  access_mode VARCHAR(24) NOT NULL COMMENT 'MERCHANT_OWN / SERVICE_PROVIDER / PLATFORM_SUB',
  funds_path VARCHAR(24) NOT NULL COMMENT 'DIRECT / AGGREGATED',
  parent_pay_merchant_id BIGINT NULL COMMENT 'SERVICE_PROVIDER / PLATFORM_SUB 时挂在哪个平台级收款商户下（pay_merchant.id）',
  legal_form VARCHAR(24) NULL COMMENT '通道进件档位（小微 / 个体户 / 企业…），不是法律形态真源',
  current_version INT NOT NULL DEFAULT 1 COMMENT '当前资料版本',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT / SUBMITTED / CHANNEL_REVIEW / NEED_SIGN / APPROVED / REJECTED / CANCELLED',
  channel_apply_no VARCHAR(64) NULL COMMENT '渠道侧申请单号（当前版本）',
  sign_url VARCHAR(512) NULL COMMENT 'NEED_SIGN 时渠道给的签约链接',
  reject_reason VARCHAR(512) NULL COMMENT '当前版本驳回原因，原样给商家看；历史原因在版本表',
  pay_merchant_id BIGINT NULL COMMENT 'APPROVED 后产出的收款商户 pay_merchant.id',
  entity_name_snapshot VARCHAR(128) NOT NULL COMMENT '建单时门店所属主体名快照',
  store_name_snapshot VARCHAR(128) NOT NULL COMMENT '建单时门店名快照',
  initiator_type VARCHAR(24) NOT NULL COMMENT 'BIZ 商家自助 / OPS 运营代办',
  initiator_no VARCHAR(64) NOT NULL COMMENT '发起人（审计字段，与 created_by 同口径，不作关联）',
  submitted_at DATETIME(3) NULL COMMENT '最近一次提交（UTC）',
  decided_at DATETIME(3) NULL COMMENT '渠道给出终态的时间',
  inflight_key VARCHAR(100) NULL COMMENT '在途唯一键：SUBMITTED / CHANNEL_REVIEW / NEED_SIGN 时 = store_id|pay_channel，否则 NULL；服务同事务维护（防 R06 重复进件）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_onboarding_1 (onboarding_no),
  UNIQUE KEY uk_pay_onboarding_2 (inflight_key),
  KEY idx_pay_onboarding_1 (store_id,pay_channel),
  KEY idx_pay_onboarding_2 (status,submitted_at)
) COMMENT='进件单：某门店向某渠道申请收款能力。资料与每次提交是版本；成功产出 pay_merchant';

-- pay-domain/onboarding · 进件提交版本：每次提交一行，外部单号每次唯一（修 R04）；驳回原因随版本保留（修 R23）
CREATE TABLE pay_onboarding_version (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_onboarding_id BIGINT NOT NULL COMMENT '进件单 pay_onboarding.id',
  version_index INT NOT NULL COMMENT '版本序号，从 1 起',
  out_request_no VARCHAR(64) NOT NULL COMMENT '提交给渠道的外部单号（out_request_no / out_biz_no），每版本唯一',
  materials_sha256 CHAR(64) NOT NULL COMMENT '本版资料规范化摘要，防止提交后资料被改',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT / SUBMITTED / CHANNEL_REVIEW / NEED_SIGN / APPROVED / REJECTED / SUPERSEDED',
  channel_apply_no VARCHAR(64) NULL COMMENT '渠道申请单号',
  result_code VARCHAR(64) NULL COMMENT '渠道结果码',
  reject_reason VARCHAR(512) NULL COMMENT '该版本驳回原因',
  submitted_by VARCHAR(64) NULL COMMENT '提交人（审计字段）',
  submitted_at DATETIME(3) NULL COMMENT '提交时间',
  decided_at DATETIME(3) NULL COMMENT '终态时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_onboarding_version_1 (pay_onboarding_id,version_index),
  UNIQUE KEY uk_pay_onboarding_version_2 (out_request_no),
  KEY idx_pay_onboarding_version_1 (channel_apply_no)
) COMMENT='进件提交版本：每次提交一行，外部单号每次唯一（修 R04）；驳回原因随版本保留（修 R23）';

-- pay-domain/onboarding · 进件资料：一字段一行、按版本只增。敏感字段只存密文 id 与掩码；文件存对象存储，库里只存引用与哈希
CREATE TABLE pay_onboarding_material (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_onboarding_version_id BIGINT NOT NULL COMMENT '所属版本 pay_onboarding_version.id',
  field_code VARCHAR(64) NOT NULL COMMENT '字段码：LEGAL_NAME / LICENSE_NO / ID_NUMBER / BANK_ACCOUNT / CONTACT_MOBILE / LICENSE_IMAGE …（字段字典按渠道维护）',
  field_kind VARCHAR(24) NOT NULL COMMENT 'TEXT 普通 / SENSITIVE 敏感 / FILE 文件',
  value_text VARCHAR(1024) NULL COMMENT '仅 TEXT：明文值',
  value_masked VARCHAR(255) NULL COMMENT 'SENSITIVE / FILE：展示用掩码或文件名',
  sec_secret_blob_id BIGINT NULL COMMENT '仅 SENSITIVE：密文 sec_secret_blob.id（密钥模块，命名空间 PAY）；查看明文写 sec_access_log',
  file_ref VARCHAR(255) NULL COMMENT '仅 FILE：对象存储私有桶键',
  file_sha256 CHAR(64) NULL COMMENT '仅 FILE：内容哈希',
  channel_media_id VARCHAR(255) NULL COMMENT '已上传渠道换得的媒体号（微信 media_id 等）',
  channel_media_expire_at DATETIME(3) NULL COMMENT '渠道媒体号过期时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_onboarding_material_1 (pay_onboarding_version_id,field_code),
  CONSTRAINT ck_pay_onboarding_material_1 CHECK (field_kind <> 'SENSITIVE' OR (sec_secret_blob_id IS NOT NULL AND value_text IS NULL)),
  CONSTRAINT ck_pay_onboarding_material_2 CHECK (field_kind <> 'FILE' OR file_ref IS NOT NULL)
) COMMENT='进件资料：一字段一行、按版本只增。敏感字段只存密文 id 与掩码；文件存对象存储，库里只存引用与哈希';

-- pay-domain/merchant · 收款商户 = 渠道中的商户。一个主体（经其门店）对多个收款商户；每个只属于一个渠道、一个市场、一种接入模式、一种资金路径。取代 mch_payment_merchant
CREATE TABLE pay_merchant (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_merchant_no VARCHAR(64) NOT NULL COMMENT '收款商户号（对外：回调地址、结算单）。**迁移时沿用 mch_payment_merchant.pay_merchant_no 原值**',
  owner_type VARCHAR(24) NOT NULL COMMENT 'STORE 商家门店开的 / PLATFORM 平台自己的（服务商号、平台账户、CN 平台直连号）',
  store_id BIGINT NULL COMMENT '开户门店 mch_store.id；owner_type=PLATFORM 时为 NULL。其他门店共用经 pay_merchant_binding',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码',
  market VARCHAR(8) NOT NULL COMMENT '市场码',
  currency CHAR(3) NOT NULL COMMENT '收款与结算币种',
  access_mode VARCHAR(24) NOT NULL COMMENT 'MERCHANT_OWN / SERVICE_PROVIDER / PLATFORM_SUB',
  funds_path VARCHAR(24) NOT NULL COMMENT 'DIRECT 结算到商户自己的账户（含微信二级商户）/ AGGREGATED 结算到平台账户',
  channel_merchant_id VARCHAR(64) NOT NULL COMMENT '渠道商户号：蓝新 MerchantID / LINE Pay ChannelId / PayBy Partner-Id / 微信 mchid（SP 模式为服务商号）',
  sub_merchant_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '子商户号：微信 sub_mchid / 支付宝 smid / PayBy 子商户标识；无则空串（参与唯一键）',
  parent_pay_merchant_id BIGINT NULL COMMENT 'SERVICE_PROVIDER / PLATFORM_SUB 时引用平台级收款商户 pay_merchant.id（取其密钥）',
  pay_onboarding_id BIGINT NULL COMMENT '产出它的进件单 pay_onboarding.id；存量迁移行可空',
  sec_key_id BIGINT NULL COMMENT '调用渠道用的密钥 sec_key.id（MERCHANT_OWN 必填；SP / 平台子商户取父商户的）',
  sec_key_version INT NOT NULL DEFAULT 0 COMMENT '当前密钥版本号；交易冻结此值',
  status VARCHAR(24) NOT NULL COMMENT 'ONBOARDING / ACTIVE / REFUND_ONLY / SUSPENDED / CLOSED',
  legal_form VARCHAR(24) NULL COMMENT '通道档位',
  invoice_capable TINYINT(1) NOT NULL DEFAULT 0 COMMENT '能否开票（下单前必须披露）',
  settle_account_type VARCHAR(24) NULL COMMENT 'PERSONAL_BANK_CARD / CORPORATE_BANK / MERCHANT_ID / IBAN',
  settle_account_masked VARCHAR(64) NULL COMMENT '结算账户掩码；明文只在渠道',
  settle_cycle VARCHAR(24) NULL COMMENT '渠道结算周期：T1 / T2 / WEEKLY …',
  fee_bearer VARCHAR(24) NULL COMMENT '通道费承担方：MERCHANT / PLATFORM',
  split_reversible TINYINT(1) NULL COMMENT '分账可回退授权；NULL = 未知按未授权',
  priority INT NOT NULL DEFAULT 100 COMMENT '同一支付方式多条可用路由时的选择顺序，小者优先',
  entity_name_snapshot VARCHAR(128) NULL COMMENT '开户时主体名快照（PLATFORM 为空）',
  activated_at DATETIME(3) NULL COMMENT '启用时间',
  activated_by VARCHAR(64) NULL COMMENT '启用人（审计字段）',
  refund_only_at DATETIME(3) NULL COMMENT '转 REFUND_ONLY 时间',
  closed_at DATETIME(3) NULL COMMENT '关闭时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_merchant_1 (pay_merchant_no),
  UNIQUE KEY uk_pay_merchant_2 (pay_channel,channel_merchant_id,sub_merchant_id),
  KEY idx_pay_merchant_1 (store_id,pay_channel,status),
  KEY idx_pay_merchant_2 (market,status),
  KEY idx_pay_merchant_3 (parent_pay_merchant_id),
  CONSTRAINT ck_pay_merchant_1 CHECK (owner_type = 'PLATFORM' OR store_id IS NOT NULL),
  CONSTRAINT ck_pay_merchant_2 CHECK (access_mode <> 'MERCHANT_OWN' OR sec_key_id IS NOT NULL OR status IN ('ONBOARDING','CLOSED')),
  CONSTRAINT ck_pay_merchant_3 CHECK (access_mode = 'MERCHANT_OWN' OR parent_pay_merchant_id IS NOT NULL)
) COMMENT='收款商户 = 渠道中的商户。一个主体（经其门店）对多个收款商户；每个只属于一个渠道、一个市场、一种接入模式、一种资金路径。取代 mch_payment_merchant';

-- pay-domain/merchant · 收款路由绑定：门店 × 渠道同一时刻一条 ACTIVE。取代 mch_store.pay_merchant_no 与旧表的 store_no 维度（修 R02/R03/R26）
CREATE TABLE pay_merchant_binding (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码',
  pay_merchant_id BIGINT NOT NULL COMMENT '收款商户 pay_merchant.id',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / INACTIVE',
  active_key VARCHAR(64) NULL COMMENT 'ACTIVE 时 = store_id|pay_channel，否则 NULL；服务同事务维护',
  bound_by VARCHAR(64) NOT NULL COMMENT '绑定人（审计字段）',
  bound_at DATETIME(3) NOT NULL COMMENT '绑定时间',
  unbound_at DATETIME(3) NULL COMMENT '解绑时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_merchant_binding_1 (active_key),
  KEY idx_pay_merchant_binding_1 (pay_merchant_id,status),
  KEY idx_pay_merchant_binding_2 (store_id,pay_channel)
) COMMENT='收款路由绑定：门店 × 渠道同一时刻一条 ACTIVE。取代 mch_store.pay_merchant_no 与旧表的 store_no 维度（修 R02/R03/R26）';

-- pay-domain/merchant · 收款商户开通的支付产品。只能由渠道回执或运营确认写入，禁止按主体类型推断
CREATE TABLE pay_merchant_product (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_merchant_id BIGINT NOT NULL COMMENT '收款商户 pay_merchant.id',
  pay_channel_product_id BIGINT NOT NULL COMMENT '支付产品 pay_channel_product.id',
  status VARCHAR(24) NOT NULL COMMENT 'OPEN / CLOSED',
  source VARCHAR(24) NOT NULL COMMENT 'CHANNEL_RECEIPT / OPS_CONFIRMED',
  quota_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '累计收款额度；0 = 未设置（不是额度为零）',
  quota_period VARCHAR(16) NULL COMMENT '额度周期标识，如 2026 / 2026-10',
  quota_used_minor BIGINT NOT NULL DEFAULT 0 COMMENT '本周期已用；累加必须检查乐观锁返回值（修 R24）',
  opened_at DATETIME(3) NULL COMMENT '开通时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_merchant_product_1 (pay_merchant_id,pay_channel_product_id),
  KEY idx_pay_merchant_product_1 (pay_channel_product_id,status)
) COMMENT='收款商户开通的支付产品。只能由渠道回执或运营确认写入，禁止按主体类型推断';

-- pay-domain/merchant · 收款商户状态变更留痕（启用、切换资金路径、暂停、关闭）
CREATE TABLE pay_merchant_status_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_merchant_id BIGINT NOT NULL COMMENT '收款商户 pay_merchant.id',
  from_status VARCHAR(24) NOT NULL COMMENT '原状态',
  to_status VARCHAR(24) NOT NULL COMMENT '新状态',
  reason VARCHAR(255) NOT NULL COMMENT '原因',
  pay_approval_id BIGINT NULL COMMENT '对应复核单 pay_approval.id',
  operator_no VARCHAR(64) NOT NULL COMMENT '操作人（审计字段）',
  changed_at DATETIME(3) NOT NULL COMMENT '时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_pay_merchant_status_log_1 (pay_merchant_id,changed_at)
) COMMENT='收款商户状态变更留痕（启用、切换资金路径、暂停、关闭）';


-- ════════ 支付订单、交易与退款 ════════

-- pay-domain/acquiring · 支付订单：调用方要收的一笔钱，独立于业务订单。一张支付单可有多次通道尝试（stl_payment）
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

-- pay-domain/acquiring · 退款单：商家侧发起的一次退款申请，含复核。执行由 RefundExecutionService 落 stl_payment(direction=REFUND)
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
) COMMENT='退款单：商家侧发起的一次退款申请，含复核。执行由 RefundExecutionService 落 stl_payment(direction=REFUND)';

-- pay-domain/acquiring · 退款复核规则（一行一个版本）：命中任一门槛 → 平台两人复核。某市场无 ACTIVE 规则 = 该市场全部复核
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

-- pay-domain/approval · 两人复核单（通用）：退款、出款、密钥启用、资金路径切换、收款商户启用、复核规则生效
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

-- pay-domain/acquiring · 支付异常：关单后支付、金额不符、重复支付等需要处置的事实（修 R18：关单后成功不再静默改成功）
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

-- pay-domain/acquiring · 收银会话：扫码 H5 / 托管收银台的一次性令牌，绑定一张支付单
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

-- pay-domain/acquiring · 静态码：门店 / 桌台码，顾客输金额建单。码值随机不可枚举
CREATE TABLE pay_qr_code (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  qr_code VARCHAR(64) NOT NULL COMMENT '码值（随机，出现在 URL 里）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  kind VARCHAR(24) NOT NULL COMMENT 'STORE / TABLE',
  table_ref VARCHAR(64) NULL COMMENT '桌台（餐饮）',
  currency CHAR(3) NOT NULL COMMENT '币种',
  single_limit_minor BIGINT NOT NULL COMMENT '单笔上限',
  daily_limit_minor BIGINT NOT NULL COMMENT '单码日累计上限',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / DISABLED',
  created_by_type VARCHAR(24) NOT NULL COMMENT 'BIZ / OPS',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_qr_code_1 (qr_code),
  KEY idx_pay_qr_code_1 (store_id,status)
) COMMENT='静态码：门店 / 桌台码，顾客输金额建单。码值随机不可枚举';


-- ════════ 流水 ════════

-- pay-domain/ledger · 记账凭证：一次资金事件一张，下挂借贷平衡的分录。只增不改，更正只能追加冲正凭证
CREATE TABLE pay_ledger_voucher (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  voucher_no VARCHAR(64) NOT NULL COMMENT '凭证号（给财务看的编号）',
  voucher_type VARCHAR(24) NOT NULL COMMENT 'PAY_SUCCEEDED / REFUND_SUCCEEDED / CHANNEL_FEE / PLATFORM_FEE / SETTLEMENT / PAYOUT / FEE_OFFSET / ADJUST / REVERSAL',
  ref_type VARCHAR(24) NOT NULL COMMENT 'PAY_ORDER / PAY_REFUND / STL_BILL / STL_PAYOUT / FEE_RECEIVABLE / MANUAL',
  ref_id BIGINT NULL COMMENT '引用对象 id（按 ref_type）；MANUAL 为空',
  idem_key VARCHAR(128) NOT NULL COMMENT '幂等键：voucher_type|ref_type|ref_id[|序号]，同一事件只记一次',
  reversal_of_id BIGINT NULL COMMENT '冲正哪张凭证 pay_ledger_voucher.id',
  market VARCHAR(8) NOT NULL COMMENT '市场',
  currency CHAR(3) NOT NULL COMMENT '币种（一张凭证单币种）',
  total_minor BIGINT NOT NULL COMMENT '借方合计 = 贷方合计',
  biz_date DATE NOT NULL COMMENT '业务日（按市场时区切日）',
  funds_path VARCHAR(24) NULL COMMENT '资金路径',
  source_type VARCHAR(24) NULL COMMENT '来源类型（继承支付单）',
  source_id BIGINT NULL COMMENT '来源 id',
  operator_no VARCHAR(64) NULL COMMENT '人工凭证的操作人',
  memo VARCHAR(255) NULL COMMENT '摘要',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_ledger_voucher_1 (voucher_no),
  UNIQUE KEY uk_pay_ledger_voucher_2 (idem_key),
  KEY idx_pay_ledger_voucher_1 (ref_type,ref_id),
  KEY idx_pay_ledger_voucher_2 (biz_date,voucher_type)
) COMMENT='记账凭证：一次资金事件一张，下挂借贷平衡的分录。只增不改，更正只能追加冲正凭证';

-- pay-domain/ledger · 流水分录：每一笔资金变化的复式分录行
CREATE TABLE pay_ledger_entry (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_ledger_voucher_id BIGINT NOT NULL COMMENT '所属凭证 pay_ledger_voucher.id',
  line_no INT NOT NULL COMMENT '行号',
  account VARCHAR(24) NOT NULL COMMENT 'MERCHANT_CHANNEL（DIRECT，平台外备查）/ PLATFORM_CHANNEL / MERCHANT_PAYABLE / FEE_RECEIVABLE / PLATFORM_REVENUE / CHANNEL_FEE_EXPENSE / REFUND_CLEARING',
  direction CHAR(2) NOT NULL COMMENT 'DR / CR',
  amount_minor BIGINT NOT NULL COMMENT '金额，恒为正',
  currency CHAR(3) NOT NULL COMMENT '币种',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  pay_merchant_id BIGINT NULL COMMENT '收款商户 pay_merchant.id',
  pay_channel VARCHAR(32) NULL COMMENT '渠道码',
  market VARCHAR(8) NOT NULL COMMENT '市场',
  funds_path VARCHAR(24) NULL COMMENT '资金路径',
  pay_order_id BIGINT NULL COMMENT '支付单 pay_order.id（便于按单拉流水）',
  biz_date DATE NOT NULL COMMENT '业务日',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_ledger_entry_1 (pay_ledger_voucher_id,line_no),
  KEY idx_pay_ledger_entry_1 (store_id,biz_date),
  KEY idx_pay_ledger_entry_2 (pay_merchant_id,biz_date),
  KEY idx_pay_ledger_entry_3 (account,biz_date),
  KEY idx_pay_ledger_entry_4 (pay_order_id),
  CONSTRAINT ck_pay_ledger_entry_1 CHECK (amount_minor > 0),
  CONSTRAINT ck_pay_ledger_entry_2 CHECK (direction IN ('DR','CR'))
) COMMENT='流水分录：每一笔资金变化的复式分录行';


-- ════════ 四向记录 ════════

-- pay-portal / pay-domain/log · 请求记录（① 业务方 → 支付）：第三方、内部系统、终端、商家端、运营端、收银台对支付的每一次接口调用，含鉴权失败的请求
CREATE TABLE pay_api_request_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  trace_id VARCHAR(64) NOT NULL COMMENT '追踪号：本次请求生成，向下传给渠道调用、回调、答复三张记录',
  entry VARCHAR(24) NOT NULL COMMENT '入口：OPEN_API / TERMINAL_API / INTERNAL_PORT / INTERNAL_HTTP / BIZ_API / OPS_API / CHECKOUT',
  api VARCHAR(128) NOT NULL COMMENT '接口标识，如 POST /open/pay/v1/orders、PaymentPort#createPayment',
  pay_action VARCHAR(32) NOT NULL COMMENT '动作（PayAction；不叫 action，避开 SQL 关键字）：CREATE_ONLINE / START_PAYMENT / QUERY / CLOSE / REQUEST_REFUND / VOID …',
  source_type VARCHAR(24) NOT NULL COMMENT '来源：PARTNER / INTERNAL / TERMINAL / BIZ / OPS / CHECKOUT',
  source_id BIGINT NULL COMMENT '来源 id：pay_app.id / pay_terminal.id / 账号 id（按 source_type）',
  app_no VARCHAR(64) NULL COMMENT '对外 AppId（展示用，鉴权失败时也记）',
  client_type VARCHAR(24) NOT NULL COMMENT '来源端：H5 / APP_IOS / APP_ANDROID / MINI_PROGRAM / POS / SERVER / OPS_WEB / BIZ_APP',
  client_ip VARCHAR(64) NULL COMMENT '来源 IP（经可信代理头）',
  user_agent_digest CHAR(64) NULL COMMENT 'User-Agent 摘要',
  device_id VARCHAR(64) NULL COMMENT '设备：终端号或设备指纹',
  operator_no VARCHAR(64) NULL COMMENT '收银员 / 商家操作员 / 运营（审计字段）',
  biz_type VARCHAR(24) NULL COMMENT '业务类型：MALL_ORDER / FOOD_CHECK / PARTNER …',
  biz_no VARCHAR(64) NULL COMMENT '调用方业务单号（原样）',
  idempotency_key VARCHAR(128) NULL COMMENT '幂等键（Idempotency-Key 或 out_order_no / out_refund_no）',
  store_id BIGINT NULL COMMENT '门店 mch_store.id；鉴权失败或与门店无关的请求为空',
  request_amount_minor BIGINT NULL COMMENT '请求金额（最小单位）',
  request_currency CHAR(3) NULL COMMENT '请求币种',
  pay_order_id BIGINT NULL COMMENT '处理后关联的支付单 pay_order.id',
  pay_refund_id BIGINT NULL COMMENT '处理后关联的退款单 pay_refund.id',
  request_masked TEXT NULL COMMENT '请求参数脱敏副本（头部 + 体，递归脱敏）',
  request_raw_id BIGINT NULL COMMENT '请求加密原文 pay_raw_payload.id（密钥模块就绪前为空）',
  response_masked TEXT NULL COMMENT '返回给业务方的内容脱敏副本',
  http_status INT NULL COMMENT '返回的 HTTP 状态（进程内调用为空）',
  result_code VARCHAR(64) NULL COMMENT '统一结果码 PayErrorCode（成功为 OK）',
  outcome VARCHAR(24) NOT NULL COMMENT 'ACCEPTED / REJECTED_AUTH / REJECTED_PARAM / REJECTED_FORBIDDEN / DUPLICATE / ERROR',
  elapsed_ms INT NULL COMMENT '处理耗时',
  received_at DATETIME(3) NOT NULL COMMENT '收到时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_pay_api_request_log_1 (trace_id),
  KEY idx_pay_api_request_log_2 (pay_order_id),
  KEY idx_pay_api_request_log_3 (source_type,source_id,received_at),
  KEY idx_pay_api_request_log_4 (biz_type,biz_no),
  KEY idx_pay_api_request_log_5 (outcome,received_at)
) COMMENT='请求记录（① 业务方 → 支付）：第三方、内部系统、终端、商家端、运营端、收银台对支付的每一次接口调用，含鉴权失败的请求';

-- pay-channel/http · 渠道调用记录（② 支付 → 渠道）：每一次对渠道的 HTTP 调用，重试每次一行。由管线的 RecordingInterceptor 与 RecordingChannelClient 写，处理器无法绕过
CREATE TABLE pay_channel_call_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  trace_id VARCHAR(64) NOT NULL COMMENT '追踪号（来自请求记录，补查任务沿用原追踪号）',
  invocation_no VARCHAR(64) NOT NULL COMMENT '单次调用号：同一次调用的多次重试共用',
  market VARCHAR(8) NULL COMMENT '市场',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码',
  access_mode VARCHAR(24) NULL COMMENT '接入模式',
  scene VARCHAR(24) NOT NULL COMMENT '场景：ONLINE_REDIRECT / ONLINE_SDK / ONLINE_QR / IN_STORE_CARD / IN_STORE_SCAN_CUSTOMER / IN_STORE_SHOW_QR / ANY',
  operation VARCHAR(24) NOT NULL COMMENT '操作：PAY / CONFIRM / QUERY / CLOSE / VOID / REFUND / REFUND_QUERY / ONBOARDING_SUBMIT / ONBOARDING_QUERY / MEDIA_UPLOAD / STATEMENT_FETCH / PAYOUT / KEY_VERIFY',
  api VARCHAR(64) NOT NULL COMMENT '渠道接口名，如 MPG_GATEWAY / QUERY_TRADE / CREDIT_CLOSE / V3_REQUEST / V3_CONFIRM / ACQUIRE2_PLACE_ORDER',
  pay_merchant_id BIGINT NULL COMMENT '以哪个收款商户的身份调 pay_merchant.id',
  ref_type VARCHAR(24) NULL COMMENT 'PAY_ORDER / PAY_REFUND / ONBOARDING / STATEMENT / PAYOUT / KEY',
  ref_id BIGINT NULL COMMENT '业务对象 id（按 ref_type）',
  pay_order_id BIGINT NULL COMMENT '支付单 pay_order.id',
  stl_payment_id BIGINT NULL COMMENT '通道尝试 stl_payment.id',
  out_trade_no VARCHAR(64) NULL COMMENT '发给渠道的单号（字符串原样，便于按用户报障单号检索）',
  attempt INT NOT NULL DEFAULT 1 COMMENT '本次调用的第几次发送',
  retry_of_id BIGINT NULL COMMENT '重试自哪一行 pay_channel_call_log.id',
  source_type VARCHAR(24) NOT NULL COMMENT '来源：PARTNER / INTERNAL / TERMINAL / BIZ / OPS / CHECKOUT',
  source_id BIGINT NULL COMMENT '来源 id：pay_app.id / pay_terminal.id / 账号 id（按 source_type）',
  client_type VARCHAR(24) NOT NULL COMMENT '来源端：H5 / APP_IOS / APP_ANDROID / MINI_PROGRAM / POS / SERVER / OPS_WEB / BIZ_APP',
  request_amount_minor BIGINT NULL COMMENT '请求金额（最小单位）',
  request_currency CHAR(3) NULL COMMENT '请求币种',
  channel_amount_text VARCHAR(32) NULL COMMENT '按渠道精度换算后实际发给渠道的金额字符串，如 TWD "1200"、AED "35.50"',
  amount_scale TINYINT NULL COMMENT '换算所用精度（pay_channel_product_market.amount_scale）',
  response_amount_minor BIGINT NULL COMMENT '渠道响应声明的金额（最小单位）',
  response_currency CHAR(3) NULL COMMENT '渠道响应币种',
  fee_minor BIGINT NULL COMMENT '渠道手续费（以回执为准）',
  http_method VARCHAR(8) NOT NULL COMMENT 'GET / POST',
  url_path VARCHAR(255) NOT NULL COMMENT '路径（不含主机与查询串里的密钥）',
  request_masked TEXT NULL COMMENT '请求脱敏副本（递归脱敏）',
  request_raw_id BIGINT NULL COMMENT '请求加密原文 pay_raw_payload.id',
  response_masked TEXT NULL COMMENT '响应脱敏副本',
  response_raw_id BIGINT NULL COMMENT '响应加密原文 pay_raw_payload.id',
  http_status INT NULL COMMENT 'HTTP 状态码；超时为空',
  elapsed_ms INT NULL COMMENT '耗时',
  channel_trade_no VARCHAR(64) NULL COMMENT '响应里的通道单号',
  outcome VARCHAR(24) NOT NULL COMMENT '传输结果：SENT / RESPONDED / TIMEOUT / CONNECT_FAILED / REJECTED_BEFORE_SEND',
  outcome_class VARCHAR(24) NULL COMMENT '统一结果：SUCCEEDED / PENDING / FAILED / UNKNOWN（超时、5xx、未登记渠道码一律 UNKNOWN）',
  unified_error_code VARCHAR(64) NULL COMMENT '统一错误码 PayErrorCode',
  error_code VARCHAR(64) NULL COMMENT '渠道原始错误码',
  error_msg VARCHAR(255) NULL COMMENT '渠道原始错误信息',
  fault_policy VARCHAR(128) NULL COMMENT '本次生效的容错策略摘要（超时 / 重试次数 / 是否可重发）',
  breaker_state VARCHAR(24) NULL COMMENT '发送时熔断器状态：CLOSED / HALF_OPEN / OPEN',
  sent_at DATETIME(3) NOT NULL COMMENT '发出时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_pay_channel_call_log_1 (trace_id),
  KEY idx_pay_channel_call_log_2 (invocation_no),
  KEY idx_pay_channel_call_log_3 (pay_order_id),
  KEY idx_pay_channel_call_log_4 (stl_payment_id),
  KEY idx_pay_channel_call_log_5 (out_trade_no),
  KEY idx_pay_channel_call_log_6 (ref_type,ref_id),
  KEY idx_pay_channel_call_log_7 (pay_channel,sent_at),
  KEY idx_pay_channel_call_log_8 (outcome_class,sent_at)
) COMMENT='渠道调用记录（② 支付 → 渠道）：每一次对渠道的 HTTP 调用，重试每次一行。由管线的 RecordingInterceptor 与 RecordingChannelClient 写，处理器无法绕过';

-- pay-domain/callback · 回调记录（③ 渠道 → 支付）：统一回调入口收到的每一条通知（含浏览器跳回）。先落库、再验签处理
CREATE TABLE pay_callback_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  trace_id VARCHAR(64) NULL COMMENT '追踪号：按单回查到原支付单的追踪号（解析不出时为空）',
  pay_channel VARCHAR(32) NOT NULL COMMENT '路径里的渠道码',
  notify_type VARCHAR(24) NOT NULL COMMENT 'PAY / REFUND / ONBOARDING / PAYOUT / RETURN',
  path_ref VARCHAR(64) NOT NULL COMMENT '路径里的引用原文（pay_merchant_no 或 onboarding_no，对外单号）',
  route_style VARCHAR(24) NOT NULL COMMENT 'PATH 子路径 / QUERY 参数 / LEGACY 旧地址别名',
  pay_merchant_id BIGINT NULL COMMENT '解析出的收款商户 pay_merchant.id',
  pay_onboarding_id BIGINT NULL COMMENT '解析出的进件单 pay_onboarding.id',
  http_method VARCHAR(8) NOT NULL COMMENT 'GET / POST',
  remote_ip VARCHAR(64) NULL COMMENT '来源 IP（经可信代理头）',
  headers_masked TEXT NULL COMMENT '请求头脱敏副本',
  body_masked TEXT NULL COMMENT '报文脱敏副本（验签前仅存指纹 + 前缀）',
  pay_raw_payload_id BIGINT NULL COMMENT '完整原文 pay_raw_payload.id（含头与体；密钥模块就绪前为空）',
  raw_sha256 CHAR(64) NOT NULL COMMENT '原文哈希（重复通知识别）',
  verify_result VARCHAR(24) NOT NULL COMMENT 'PENDING / PASSED / FAILED / MERCHANT_MISMATCH / STALE',
  notify_amount_minor BIGINT NULL COMMENT '通知里声明的金额（最小单位）',
  notify_currency CHAR(3) NULL COMMENT '通知币种',
  fee_minor BIGINT NULL COMMENT '通知里的手续费',
  amount_matched TINYINT(1) NULL COMMENT '通知金额与支付单 / 退款单是否一致；不一致记 pay_exception 且不推进成功',
  unified_status VARCHAR(24) NULL COMMENT '映射后的统一状态：SUCCEEDED / FAILED / CLOSED / REFUNDED …',
  outcome VARCHAR(24) NOT NULL COMMENT 'RECEIVED / APPLIED / DUPLICATE / REJECTED / ERROR',
  outcome_reason VARCHAR(255) NULL COMMENT '原因',
  pay_order_id BIGINT NULL COMMENT '关联支付单 pay_order.id',
  pay_refund_id BIGINT NULL COMMENT '关联退款单 pay_refund.id',
  stl_payment_id BIGINT NULL COMMENT '关联通道尝试 stl_payment.id',
  out_trade_no VARCHAR(64) NULL COMMENT '报文里的我方单号（原样）',
  channel_trade_no VARCHAR(64) NULL COMMENT '通道单号',
  ack_body VARCHAR(255) NULL COMMENT '回给渠道的应答',
  replay_of_id BIGINT NULL COMMENT '重放自哪条回调 pay_callback_log.id',
  replayed_by VARCHAR(64) NULL COMMENT '重放操作人',
  received_at DATETIME(3) NOT NULL COMMENT '收到时间',
  handled_at DATETIME(3) NULL COMMENT '处理完成时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_pay_callback_log_1 (trace_id),
  KEY idx_pay_callback_log_2 (pay_order_id),
  KEY idx_pay_callback_log_3 (stl_payment_id),
  KEY idx_pay_callback_log_4 (out_trade_no),
  KEY idx_pay_callback_log_5 (channel_trade_no),
  KEY idx_pay_callback_log_6 (pay_channel,received_at),
  KEY idx_pay_callback_log_7 (outcome,received_at),
  KEY idx_pay_callback_log_8 (raw_sha256)
) COMMENT='回调记录（③ 渠道 → 支付）：统一回调入口收到的每一条通知（含浏览器跳回）。先落库、再验签处理';

-- pay-domain/log · 加密原文：调用请求 / 响应、回调原文、对账单原件。与记录表分开，便于按期限清理与单独授权
CREATE TABLE pay_raw_payload (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  kind VARCHAR(24) NOT NULL COMMENT 'CALL_REQUEST / CALL_RESPONSE / CALLBACK / STATEMENT',
  storage VARCHAR(24) NOT NULL COMMENT 'DB / OBJECT（大文件如对账单走对象存储）',
  cipher_text MEDIUMTEXT NULL COMMENT 'storage=DB：AES-GCM 密文',
  object_key VARCHAR(255) NULL COMMENT 'storage=OBJECT：对象存储键（对象本身加密）',
  data_key_cipher VARCHAR(512) NOT NULL COMMENT '被主密钥包裹的数据密钥（经密钥模块 API 加解密）',
  sec_master_key_id BIGINT NOT NULL COMMENT '包裹所用主密钥 sec_master_key.id',
  sha256 CHAR(64) NOT NULL COMMENT '明文哈希（举证用）',
  size_bytes INT NOT NULL COMMENT '明文字节数',
  retain_until DATE NOT NULL COMMENT '保留至（合规期限，Q4）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_pay_raw_payload_1 (retain_until),
  KEY idx_pay_raw_payload_2 (sec_master_key_id),
  CONSTRAINT ck_pay_raw_payload_1 CHECK ((storage = 'DB' AND cipher_text IS NOT NULL) OR (storage = 'OBJECT' AND object_key IS NOT NULL))
) COMMENT='加密原文：调用请求 / 响应、回调原文、对账单原件。与记录表分开，便于按期限清理与单独授权';

-- pay-domain/notify · 答复记录（④ 支付 → 业务方）：一次对业务方的通知，内部系统走领域事件、外部系统走签名 webhook、终端走推送。取代 pay_webhook_delivery
CREATE TABLE pay_biz_notify (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  trace_id VARCHAR(64) NOT NULL COMMENT '触发它的那次请求 / 回调 / 补查的追踪号',
  event_type VARCHAR(32) NOT NULL COMMENT 'PAYMENT_SUCCEEDED / PAYMENT_FAILED / PAYMENT_CLOSED / REFUND_SUCCEEDED / REFUND_FAILED / REFUND_REVIEW_REJECTED',
  event_key VARCHAR(128) NOT NULL COMMENT '事件幂等键：event_type|对象 id|状态版本；推送体里同样带上，供业务方去重',
  state_version INT NOT NULL COMMENT '对象状态版本：业务方据此丢弃旧通知，保证顺序',
  target_type VARCHAR(24) NOT NULL COMMENT 'INTERNAL_EVENT 内部系统（Outbox 领域事件）/ WEBHOOK 外部系统 / TERMINAL 终端推送',
  pay_app_id BIGINT NOT NULL COMMENT '接收方 pay_app.id',
  target_url VARCHAR(512) NULL COMMENT 'WEBHOOK 的目标地址（支付单 notify_url 优先，否则接入方 webhook_url）',
  pay_order_id BIGINT NULL COMMENT '支付单 pay_order.id',
  pay_refund_id BIGINT NULL COMMENT '退款单 pay_refund.id',
  notify_amount_minor BIGINT NULL COMMENT '告诉业务方的金额（最小单位）',
  notify_currency CHAR(3) NULL COMMENT '币种',
  payload_masked TEXT NOT NULL COMMENT '推送内容（脱敏）',
  status VARCHAR(24) NOT NULL COMMENT 'PENDING / DELIVERED / FAILED / GIVEN_UP',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已投递次数',
  next_at DATETIME(3) NULL COMMENT '下次投递时间（指数退避）',
  delivered_at DATETIME(3) NULL COMMENT '送达时间',
  given_up_at DATETIME(3) NULL COMMENT '放弃时间（≥ 24h 仍失败）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_biz_notify_1 (target_type,pay_app_id,event_key),
  KEY idx_pay_biz_notify_1 (status,next_at),
  KEY idx_pay_biz_notify_2 (pay_order_id),
  KEY idx_pay_biz_notify_3 (trace_id)
) COMMENT='答复记录（④ 支付 → 业务方）：一次对业务方的通知，内部系统走领域事件、外部系统走签名 webhook、终端走推送。取代 pay_webhook_delivery';

-- pay-domain/notify · 答复投递记录：pay_biz_notify 的每一次投递一行（含手动重推）
CREATE TABLE pay_biz_notify_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_biz_notify_id BIGINT NOT NULL COMMENT '所属通知 pay_biz_notify.id',
  attempt INT NOT NULL COMMENT '第几次投递',
  trigger_type VARCHAR(24) NOT NULL COMMENT 'AUTO 自动 / MANUAL 运营手动重推',
  triggered_by VARCHAR(64) NULL COMMENT '手动重推的操作人',
  sent_at DATETIME(3) NOT NULL COMMENT '发出时间',
  http_status INT NULL COMMENT '对方返回的 HTTP 状态（INTERNAL_EVENT 为空）',
  response_masked VARCHAR(1024) NULL COMMENT '对方响应（截断、脱敏）',
  elapsed_ms INT NULL COMMENT '耗时',
  outcome VARCHAR(24) NOT NULL COMMENT 'DELIVERED / REJECTED / TIMEOUT / ERROR',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_biz_notify_attempt_1 (pay_biz_notify_id,attempt)
) COMMENT='答复投递记录：pay_biz_notify 的每一次投递一行（含手动重推）';


-- ════════ 渠道运行 ════════

-- pay-channel/fault · 「未知」补查任务：渠道调用结果不明（超时、5xx、未登记渠道码）时登记，按退避自动查询，直到有结论或转人工
CREATE TABLE pay_channel_followup (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  invocation_no VARCHAR(64) NOT NULL COMMENT '原调用号（pay_channel_call_log.invocation_no）',
  trace_id VARCHAR(64) NOT NULL COMMENT '原追踪号：补查产生的调用记录沿用它',
  operation VARCHAR(24) NOT NULL COMMENT '原操作：PAY / CONFIRM / REFUND / CLOSE / VOID / PAYOUT',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码',
  pay_merchant_id BIGINT NULL COMMENT '收款商户 pay_merchant.id',
  pay_order_id BIGINT NULL COMMENT '支付单 pay_order.id',
  stl_payment_id BIGINT NULL COMMENT '通道尝试 stl_payment.id',
  pay_refund_id BIGINT NULL COMMENT '退款单 pay_refund.id',
  status VARCHAR(24) NOT NULL COMMENT 'PENDING / RESOLVED / ESCALATED / CANCELLED',
  open_key VARCHAR(100) NULL COMMENT 'PENDING 时 = operation|stl_payment_id|pay_refund_id：同一对象同一操作只有一个进行中补查',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已补查次数',
  next_at DATETIME(3) NOT NULL COMMENT '下次补查时间（指数退避）',
  last_result VARCHAR(255) NULL COMMENT '最近一次补查结果摘要',
  resolved_at DATETIME(3) NULL COMMENT '得出结论的时间',
  pay_exception_id BIGINT NULL COMMENT '超过上限转人工时建的 pay_exception.id',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_channel_followup_1 (open_key),
  KEY idx_pay_channel_followup_1 (status,next_at),
  KEY idx_pay_channel_followup_2 (pay_order_id),
  KEY idx_pay_channel_followup_3 (invocation_no)
) COMMENT='「未知」补查任务：渠道调用结果不明（超时、5xx、未登记渠道码）时登记，按退避自动查询，直到有结论或转人工';

-- pay-channel/lifecycle · 渠道适配器生命周期日志：每个「渠道 × 收款商户」运行时实例的一次状态变化
CREATE TABLE pay_channel_lifecycle_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码',
  pay_merchant_id BIGINT NULL COMMENT '收款商户 pay_merchant.id；渠道级状态（整个渠道停用）为空',
  from_state VARCHAR(24) NOT NULL COMMENT '原状态：REGISTERED / INITIALIZED / ACTIVE / DEGRADED / SUSPENDED / DRAINING / STOPPED',
  to_state VARCHAR(24) NOT NULL COMMENT '新状态',
  reason VARCHAR(255) NOT NULL COMMENT '原因：熔断打开、健康检查失败、运营停用、凭据吊销、应用关闭…',
  operator_no VARCHAR(64) NOT NULL COMMENT '操作人（系统触发为 SYSTEM）',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_pay_channel_lifecycle_log_1 (pay_channel,occurred_at),
  KEY idx_pay_channel_lifecycle_log_2 (pay_merchant_id,occurred_at)
) COMMENT='渠道适配器生命周期日志：每个「渠道 × 收款商户」运行时实例的一次状态变化';


-- ════════ 接入方与终端 ════════

-- pay-domain/openapp · 接入方：第三方系统或内部系统（内部系统也注册，用于来源可拆）
CREATE TABLE pay_app (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  app_no VARCHAR(64) NOT NULL COMMENT '对外 AppId（请求头 X-App-Id 传的就是它）',
  name VARCHAR(128) NOT NULL COMMENT '名称',
  owner_type VARCHAR(24) NOT NULL COMMENT 'PARTNER / INTERNAL',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / SUSPENDED / REVOKED',
  scopes VARCHAR(255) NOT NULL COMMENT '逗号分隔：pay:create,pay:read,pay:refund,ledger:read,settle:read',
  ip_whitelist VARCHAR(1024) NULL COMMENT 'IP / CIDR 白名单',
  webhook_url VARCHAR(512) NULL COMMENT '结果推送地址',
  return_url_whitelist VARCHAR(1024) NULL COMMENT '允许的跳回域名',
  rate_limit_qps INT NOT NULL DEFAULT 20 COMMENT '限流',
  daily_refund_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '日退款上限；0 = 不允许退款',
  sandbox TINYINT(1) NOT NULL DEFAULT 0 COMMENT '沙箱 app（路由到 TEST 渠道）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_app_1 (app_no),
  KEY idx_pay_app_1 (owner_type,status)
) COMMENT='接入方：第三方系统或内部系统（内部系统也注册，用于来源可拆）';

-- pay-domain/openapp · 接入方授权：门店授权某 app 代本店收款或退款（原 pay_app_merchant，按命名基准改名）
CREATE TABLE pay_app_grant (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_app_id BIGINT NOT NULL COMMENT '接入方 pay_app.id',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  actions VARCHAR(128) NOT NULL COMMENT '逗号分隔：COLLECT,REFUND,READ',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / REVOKED',
  granted_by VARCHAR(64) NOT NULL COMMENT '授权人（商家，审计字段）',
  granted_at DATETIME(3) NOT NULL COMMENT '授权时间',
  revoked_at DATETIME(3) NULL COMMENT '撤销时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_app_grant_1 (pay_app_id,store_id),
  KEY idx_pay_app_grant_1 (store_id,status)
) COMMENT='接入方授权：门店授权某 app 代本店收款或退款（原 pay_app_merchant，按命名基准改名）';

-- pay-domain/openapp · 签名 nonce 防重放（无 Redis 时的落库实现）；按 expire_at 定期清理
CREATE TABLE pay_api_nonce (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  caller_type VARCHAR(24) NOT NULL COMMENT 'APP / TERMINAL',
  caller_id BIGINT NOT NULL COMMENT 'pay_app.id 或 pay_terminal.id',
  nonce VARCHAR(64) NOT NULL COMMENT '随机串',
  expire_at DATETIME(3) NOT NULL COMMENT '过期（= 请求时间 + 300s）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_api_nonce_1 (caller_type,caller_id,nonce),
  KEY idx_pay_api_nonce_1 (expire_at)
) COMMENT='签名 nonce 防重放（无 Redis 时的落库实现）；按 expire_at 定期清理';

-- pay-domain/terminal · 刷卡机终端（自研 App）：绑定收款商户与门店
CREATE TABLE pay_terminal (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  terminal_sn VARCHAR(64) NOT NULL COMMENT '终端序列号（设备上可见，对外）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  pay_merchant_id BIGINT NOT NULL COMMENT '绑定的收款商户 pay_merchant.id',
  model VARCHAR(64) NOT NULL COMMENT '机型',
  reader_kit VARCHAR(64) NULL COMMENT '读卡套件型号 / 版本（按渠道认证清单校验）',
  app_version VARCHAR(32) NULL COMMENT '终端 App 版本',
  status VARCHAR(24) NOT NULL COMMENT 'REGISTERED / ACTIVE / LOST / RETIRED',
  activation_code_sha256 CHAR(64) NULL COMMENT '一次性激活码哈希',
  activation_expire_at DATETIME(3) NULL COMMENT '激活码过期（10 分钟）',
  device_fingerprint VARCHAR(128) NULL COMMENT '激活时设备指纹',
  sec_key_id BIGINT NULL COMMENT '设备密钥 sec_key.id（owner_type=TERMINAL），激活时生成',
  daily_refund_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '单终端日退款上限',
  last_seen_at DATETIME(3) NULL COMMENT '最近心跳',
  activated_at DATETIME(3) NULL COMMENT '激活时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_terminal_1 (terminal_sn),
  KEY idx_pay_terminal_1 (store_id,status),
  KEY idx_pay_terminal_2 (pay_merchant_id)
) COMMENT='刷卡机终端（自研 App）：绑定收款商户与门店';

-- pay-domain/terminal · 店长授权码：终端撤销 / 退款的授权，由商家端设置，只存哈希
CREATE TABLE pay_manager_code (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  manager_account_id BIGINT NOT NULL COMMENT '店长账号 mch_account.id（核心系统）',
  code_hash VARCHAR(128) NOT NULL COMMENT '授权码哈希（加盐慢哈希）',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / REVOKED',
  failed_attempts INT NOT NULL DEFAULT 0 COMMENT '连续失败次数（超限锁定）',
  locked_until DATETIME(3) NULL COMMENT '锁定至',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_manager_code_1 (store_id,manager_account_id)
) COMMENT='店长授权码：终端撤销 / 退款的授权，由商家端设置，只存哈希';

-- pay-domain/terminal · 终端日结：终端上报与服务端汇总比对
CREATE TABLE pay_terminal_batch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_terminal_id BIGINT NOT NULL COMMENT '终端 pay_terminal.id',
  biz_date DATE NOT NULL COMMENT '营业日',
  currency CHAR(3) NOT NULL COMMENT '币种',
  reported_count INT NOT NULL COMMENT '终端上报笔数',
  reported_minor BIGINT NOT NULL COMMENT '终端上报金额',
  reported_void_count INT NOT NULL DEFAULT 0 COMMENT '撤销笔数',
  reported_void_minor BIGINT NOT NULL DEFAULT 0 COMMENT '撤销金额',
  server_count INT NULL COMMENT '服务端汇总笔数',
  server_minor BIGINT NULL COMMENT '服务端汇总金额',
  status VARCHAR(24) NOT NULL COMMENT 'REPORTED / MATCHED / MISMATCHED',
  operator_no VARCHAR(64) NOT NULL COMMENT '日结收银员（审计字段）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_terminal_batch_1 (pay_terminal_id,biz_date)
) COMMENT='终端日结：终端上报与服务端汇总比对';


-- ════════ 密钥管理 ════════

-- secret（独立模块） · 主密钥登记：按命名空间与环境登记包裹数据密钥的主密钥元数据。本表不存任何密钥材料
CREATE TABLE sec_master_key (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  kms_key_id VARCHAR(128) NOT NULL COMMENT 'KMS key id 或环境注入别名（是名字，不是密钥）',
  provider VARCHAR(24) NOT NULL COMMENT 'ENV 部署注入 / KMS 云 KMS / HSM 硬件',
  namespace VARCHAR(24) NOT NULL COMMENT 'PAY / LOGISTICS / INVENTORY / NOTIFY / PLATFORM：每个域一套主密钥，进程只能解本域',
  env VARCHAR(24) NOT NULL COMMENT 'SANDBOX / PRODUCTION：两套环境主密钥必须不同',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE 用于新加密 / DECRYPT_ONLY 只解密（轮换后的旧主密钥）/ RETIRED 已无密文引用',
  active_key VARCHAR(48) NULL COMMENT 'ACTIVE 时 = namespace|env：每个命名空间每个环境同一时刻一个 ACTIVE',
  activated_at DATETIME(3) NULL COMMENT '启用时间',
  rotated_at DATETIME(3) NULL COMMENT '被新主密钥替换的时间',
  retired_at DATETIME(3) NULL COMMENT '退役时间（全部密文已重新包裹）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sec_master_key_1 (kms_key_id),
  UNIQUE KEY uk_sec_master_key_2 (active_key)
) COMMENT='主密钥登记：按命名空间与环境登记包裹数据密钥的主密钥元数据。本表不存任何密钥材料';

-- secret（独立模块） · 密文存储：密钥私密材料与各域敏感字段的信封加密密文。数据密钥由主密钥包裹，主密钥不落库
CREATE TABLE sec_secret_blob (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  namespace VARCHAR(24) NOT NULL COMMENT '命名空间（同 sec_master_key.namespace）',
  purpose VARCHAR(24) NOT NULL COMMENT 'KEY_MATERIAL 密钥材料 / SENSITIVE_FIELD 敏感字段（如进件证件号、银行账号）',
  owner_type VARCHAR(24) NOT NULL COMMENT 'KEY_VERSION / ONBOARDING_MATERIAL / …（调用方模块的对象类型）',
  owner_id BIGINT NOT NULL COMMENT '归属对象 id（sec_key_version.id 或 pay_onboarding_material.id 等）',
  env VARCHAR(24) NOT NULL COMMENT 'SANDBOX / PRODUCTION',
  cipher_text TEXT NOT NULL COMMENT 'AES-256-GCM 密文（Base64，含 IV 与认证标签）',
  data_key_cipher VARCHAR(512) NOT NULL COMMENT '被主密钥包裹的数据密钥',
  sec_master_key_id BIGINT NOT NULL COMMENT '包裹所用主密钥 sec_master_key.id',
  algorithm VARCHAR(32) NOT NULL COMMENT 'AES-256-GCM',
  aad_sha256 CHAR(64) NOT NULL COMMENT '附加认证数据（id + namespace + owner_type + owner_id + env）的摘要：密文被挪到别的行即解密失败',
  rewrapped_at DATETIME(3) NULL COMMENT '最近一次主密钥轮换重新包裹的时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_sec_secret_blob_1 (namespace,owner_type,owner_id),
  KEY idx_sec_secret_blob_2 (sec_master_key_id)
) COMMENT='密文存储：密钥私密材料与各域敏感字段的信封加密密文。数据密钥由主密钥包裹，主密钥不落库';

-- secret（独立模块） · 密钥策略：按命名空间、归属类型与环境规定有效期、到期提醒、轮换重叠期、双人控制
CREATE TABLE sec_key_policy (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  namespace VARCHAR(24) NOT NULL COMMENT '命名空间',
  owner_type VARCHAR(24) NOT NULL COMMENT 'PAY_MERCHANT / CHANNEL_PLATFORM / PARTNER_APP / INTERNAL_APP / TERMINAL / PROVIDER …',
  env VARCHAR(24) NOT NULL COMMENT 'SANDBOX / PRODUCTION',
  max_age_days INT NOT NULL DEFAULT 0 COMMENT '版本最长使用天数；0 = 不强制（以证书有效期为准）',
  warn_before_days INT NOT NULL DEFAULT 30 COMMENT '到期前多少天提醒',
  overlap_days INT NOT NULL DEFAULT 7 COMMENT '轮换重叠期：新版本启用后旧版本仍可用的天数',
  dual_control TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启用 / 吊销是否需要两人',
  verify_before_enable TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启用前是否必须测试调用通过',
  reveal_on_issue_only TINYINT(1) NOT NULL DEFAULT 1 COMMENT '平台生成的密钥明文是否只在发放时展示一次',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sec_key_policy_1 (namespace,owner_type,env)
) COMMENT='密钥策略：按命名空间、归属类型与环境规定有效期、到期提醒、轮换重叠期、双人控制';

-- secret（独立模块） · 密钥：调用外部 API 与被外部调用的每一把密钥。支付命名空间下包括：我方调渠道、渠道平台公钥、第三方与内部系统调我方、刷卡机。材料在版本表
CREATE TABLE sec_key (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  namespace VARCHAR(24) NOT NULL COMMENT 'PAY / LOGISTICS / INVENTORY / NOTIFY / PLATFORM',
  name VARCHAR(128) NOT NULL COMMENT '名称（给人看）',
  owner_type VARCHAR(24) NOT NULL COMMENT 'PAY_MERCHANT 收款商户调渠道 / CHANNEL_PLATFORM 渠道平台公钥与证书 / PARTNER_APP 第三方接入方 / INTERNAL_APP 内部系统 / TERMINAL 刷卡机 / PROVIDER 其他域的外部服务商',
  owner_id BIGINT NOT NULL COMMENT '归属对象 id：pay_merchant.id / pay_app.id / pay_terminal.id …',
  counterparty VARCHAR(32) NULL COMMENT '对端系统码：NEWEBPAY / LINEPAY / PAYBY / WECHAT / ALIPAY / KUAIDI100 …',
  direction VARCHAR(24) NOT NULL COMMENT 'OUTBOUND 我方调外部时使用 / INBOUND 外部调我方时我方验证 / COUNTERPARTY_VERIFY 验对端响应与回调',
  key_usage VARCHAR(24) NOT NULL COMMENT 'SIGN 签名 / VERIFY 验签 / HMAC / ENCRYPT 加解密 / MTLS 双向证书',
  algorithm VARCHAR(32) NOT NULL COMMENT 'HMAC_SHA256 / RSA_SHA256 / AES_256_CBC / AES_256_GCM / ECDSA_P256 …',
  material_type VARCHAR(24) NOT NULL COMMENT 'SYMMETRIC_SECRET / PRIVATE_KEY / PUBLIC_KEY / CERTIFICATE / COMPOSITE（蓝新 HashKey + HashIV）',
  env VARCHAR(24) NOT NULL COMMENT 'SANDBOX / PRODUCTION：生产密钥永不进入沙箱',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT / ACTIVE / SUSPENDED / REVOKED / EXPIRED',
  current_version INT NOT NULL DEFAULT 0 COMMENT '当前 ACTIVE 版本号；0 = 尚无',
  sec_key_policy_id BIGINT NULL COMMENT '适用策略 sec_key_policy.id；空 = 按 namespace + owner_type + env 取默认',
  expires_at DATETIME(3) NULL COMMENT '当前版本到期时间（证书有效期或策略最长使用期，取早者）',
  last_used_at DATETIME(3) NULL COMMENT '最近使用（异步刷新）',
  use_count BIGINT NOT NULL DEFAULT 0 COMMENT '累计使用次数（异步累加，近似值）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sec_key_1 (namespace,owner_type,owner_id,direction,key_usage,env),
  KEY idx_sec_key_1 (status,expires_at),
  KEY idx_sec_key_2 (counterparty,status)
) COMMENT='密钥：调用外部 API 与被外部调用的每一把密钥。支付命名空间下包括：我方调渠道、渠道平台公钥、第三方与内部系统调我方、刷卡机。材料在版本表';

-- secret（独立模块） · 密钥版本：一把密钥的每一份材料。私密材料只存密文 id，公钥与证书可明文存放
CREATE TABLE sec_key_version (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  sec_key_id BIGINT NOT NULL COMMENT '所属密钥 sec_key.id',
  version_index INT NOT NULL COMMENT '版本号，从 1 起（交易冻结的是这个号）',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT 已录入 / VERIFIED 测试通过 / ACTIVE 当前 / ROTATING 重叠期仍可用 / RETIRED 仅验旧签与在途单 / REVOKED 立即失效',
  active_key VARCHAR(32) NULL COMMENT 'ACTIVE 时 = sec_key_id：一把密钥同一时刻一个 ACTIVE 版本',
  sec_secret_blob_id BIGINT NULL COMMENT '私密材料的密文 sec_secret_blob.id（对称密钥、私钥）',
  public_material TEXT NULL COMMENT '公钥或证书 PEM（可公开，不加密）',
  fingerprint VARCHAR(64) NOT NULL COMMENT '指纹：公钥 / 证书为 SHA-256，对称密钥为 HMAC 派生前缀；唯一可回显的信息',
  external_key_id VARCHAR(64) NULL COMMENT '外部密钥标识：微信平台证书序列号、PayBy 公钥 ID、接入方 key id',
  source VARCHAR(24) NOT NULL COMMENT 'GENERATED 平台生成 / UPLOADED 人工录入 / FETCHED 从对端下载（微信平台证书）',
  valid_from DATETIME(3) NULL COMMENT '生效起（证书 notBefore）',
  valid_to DATETIME(3) NULL COMMENT '生效止（证书 notAfter / 策略最长使用期）',
  entered_by VARCHAR(64) NOT NULL COMMENT '录入或生成人',
  verified_at DATETIME(3) NULL COMMENT '测试调用通过时间',
  verify_evidence VARCHAR(128) NULL COMMENT '测试调用证据：调用方模块写入的记录引用，如 PAY:pay_channel_call_log:123（不作关联）',
  enabled_by VARCHAR(64) NULL COMMENT '启用人，必须 ≠ 录入人',
  enabled_at DATETIME(3) NULL COMMENT '启用时间',
  overlap_until DATETIME(3) NULL COMMENT '轮换后旧版本仍可用至',
  retired_at DATETIME(3) NULL COMMENT '退役时间',
  revoked_by VARCHAR(64) NULL COMMENT '吊销人',
  revoked_at DATETIME(3) NULL COMMENT '吊销时间',
  revoke_reason VARCHAR(255) NULL COMMENT '吊销原因（泄露、离职、商户解约…）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sec_key_version_1 (sec_key_id,version_index),
  UNIQUE KEY uk_sec_key_version_2 (active_key),
  KEY idx_sec_key_version_1 (sec_key_id,status),
  KEY idx_sec_key_version_2 (external_key_id),
  KEY idx_sec_key_version_3 (status,valid_to),
  CONSTRAINT ck_sec_key_version_1 CHECK (sec_secret_blob_id IS NOT NULL OR public_material IS NOT NULL),
  CONSTRAINT ck_sec_key_version_2 CHECK (enabled_by IS NULL OR enabled_by <> entered_by)
) COMMENT='密钥版本：一把密钥的每一份材料。私密材料只存密文 id，公钥与证书可明文存放';

-- secret（独立模块） · 密钥生命周期事件：创建、录入、测试、启用、轮换、退役、吊销、到期提醒、重新包裹。只增不改
CREATE TABLE sec_key_event (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  sec_key_id BIGINT NOT NULL COMMENT '密钥 sec_key.id',
  sec_key_version_id BIGINT NULL COMMENT '版本 sec_key_version.id（密钥级事件为空）',
  event_type VARCHAR(24) NOT NULL COMMENT 'CREATED / MATERIAL_ADDED / VERIFIED / VERIFY_FAILED / ENABLED / ROTATION_STARTED / RETIRED / REVOKED / SUSPENDED / RESUMED / EXPIRING_SOON / EXPIRED / REWRAPPED',
  operator_type VARCHAR(24) NOT NULL COMMENT 'OPS 运营 / BIZ 商家 / SYSTEM 系统任务',
  operator_no VARCHAR(64) NOT NULL COMMENT '操作人（审计字段）',
  approval_ref VARCHAR(128) NULL COMMENT '两人复核证据：调用方模块的复核单引用，如 PAY:pay_approval:88（不作关联）',
  reason VARCHAR(255) NULL COMMENT '原因',
  client_ip VARCHAR(64) NULL COMMENT '操作来源 IP',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_sec_key_event_1 (sec_key_id,occurred_at),
  KEY idx_sec_key_event_2 (event_type,occurred_at)
) COMMENT='密钥生命周期事件：创建、录入、测试、启用、轮换、退役、吊销、到期提醒、重新包裹。只增不改';

-- secret（独立模块） · 明文接触记录：任何人看到明文（发放时展示、查看敏感字段、查看报文原文、应急调阅）逐次记录。只增不改
CREATE TABLE sec_access_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  access_type VARCHAR(24) NOT NULL COMMENT 'REVEAL_ON_ISSUE 发放时展示 / SENSITIVE_FIELD_VIEW / RAW_PAYLOAD_VIEW / BREAK_GLASS 应急调阅',
  namespace VARCHAR(24) NOT NULL COMMENT '命名空间',
  sec_key_id BIGINT NULL COMMENT '涉及的密钥 sec_key.id',
  sec_secret_blob_id BIGINT NULL COMMENT '涉及的密文 sec_secret_blob.id',
  target_type VARCHAR(24) NULL COMMENT '其他对象类型：RAW_PAYLOAD …',
  target_id BIGINT NULL COMMENT '其他对象 id（如 pay_raw_payload.id）',
  accessor_no VARCHAR(64) NOT NULL COMMENT '查看人',
  accessor_role VARCHAR(64) NOT NULL COMMENT '查看时的岗位 / 权限码',
  reason VARCHAR(255) NOT NULL COMMENT '查看理由（必填）',
  approval_ref VARCHAR(128) NULL COMMENT 'BREAK_GLASS 必须有复核单引用',
  client_ip VARCHAR(64) NULL COMMENT '来源 IP',
  accessed_at DATETIME(3) NOT NULL COMMENT '时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  KEY idx_sec_access_log_1 (accessor_no,accessed_at),
  KEY idx_sec_access_log_2 (access_type,accessed_at),
  CONSTRAINT ck_sec_access_log_1 CHECK (sec_key_id IS NOT NULL OR sec_secret_blob_id IS NOT NULL OR target_id IS NOT NULL),
  CONSTRAINT ck_sec_access_log_2 CHECK (access_type <> 'BREAK_GLASS' OR approval_ref IS NOT NULL)
) COMMENT='明文接触记录：任何人看到明文（发放时展示、查看敏感字段、查看报文原文、应急调阅）逐次记录。只增不改';


-- ════════ 对账与结算 ════════

-- pay-domain/recon · 渠道对账单原件：按收款商户 × 账单日一份
CREATE TABLE stl_channel_statement (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_channel VARCHAR(32) NOT NULL COMMENT '渠道码',
  pay_merchant_id BIGINT NOT NULL COMMENT '收款商户 pay_merchant.id（AGGREGATED 为平台账户）',
  bill_date DATE NOT NULL COMMENT '账单日（渠道口径）',
  bill_type VARCHAR(24) NOT NULL COMMENT 'TRADE / SETTLEMENT / FEE',
  pay_raw_payload_id BIGINT NULL COMMENT '原件 pay_raw_payload.id',
  source VARCHAR(24) NOT NULL COMMENT 'DOWNLOAD / QUERY_FALLBACK（逐笔查单退化）/ MANUAL_UPLOAD',
  line_count INT NOT NULL DEFAULT 0 COMMENT '行数',
  status VARCHAR(24) NOT NULL COMMENT 'FETCHING / FETCHED / PARSED / RECONCILED / FAILED',
  fetch_attempts INT NOT NULL DEFAULT 0 COMMENT '拉取次数',
  error_msg VARCHAR(255) NULL COMMENT '失败原因',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stl_channel_statement_1 (pay_merchant_id,bill_date,bill_type),
  KEY idx_stl_channel_statement_1 (status,bill_date)
) COMMENT='渠道对账单原件：按收款商户 × 账单日一份';

-- pay-domain/recon · 对账单标准行：各渠道格式统一后的逐笔记录
CREATE TABLE stl_channel_statement_line (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  stl_channel_statement_id BIGINT NOT NULL COMMENT '对账单 stl_channel_statement.id',
  line_no INT NOT NULL COMMENT '行号',
  direction VARCHAR(24) NOT NULL COMMENT 'PAY / REFUND',
  out_trade_no VARCHAR(64) NULL COMMENT '对账单里的我方单号（原样）',
  channel_trade_no VARCHAR(64) NOT NULL COMMENT '通道单号',
  amount_minor BIGINT NOT NULL COMMENT '金额',
  fee_minor BIGINT NOT NULL DEFAULT 0 COMMENT '手续费',
  currency CHAR(3) NOT NULL COMMENT '币种',
  channel_status VARCHAR(32) NOT NULL COMMENT '渠道状态',
  trade_at DATETIME(3) NULL COMMENT '交易时间',
  match_status VARCHAR(24) NOT NULL COMMENT 'PENDING / MATCHED / DIFF',
  stl_payment_id BIGINT NULL COMMENT '匹配上的通道交易 stl_payment.id',
  stl_recon_diff_id BIGINT NULL COMMENT '差异单 stl_recon_diff.id',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stl_channel_statement_line_1 (stl_channel_statement_id,line_no),
  KEY idx_stl_channel_statement_line_1 (out_trade_no),
  KEY idx_stl_channel_statement_line_2 (channel_trade_no),
  KEY idx_stl_channel_statement_line_3 (stl_payment_id)
) COMMENT='对账单标准行：各渠道格式统一后的逐笔记录';

-- pay-domain/settle · 平台费应收（DIRECT 路径：钱不经过平台，平台费事后向商家收）
CREATE TABLE pay_fee_receivable (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  receivable_no VARCHAR(64) NOT NULL COMMENT '应收单号（对外：给商家的账单编号）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  pay_merchant_id BIGINT NOT NULL COMMENT '产生该费用的收款商户 pay_merchant.id',
  period_from DATE NOT NULL COMMENT '账期起（含）',
  period_to DATE NOT NULL COMMENT '账期止（不含）',
  currency CHAR(3) NOT NULL COMMENT '币种',
  amount_minor BIGINT NOT NULL COMMENT '应收',
  offset_minor BIGINT NOT NULL DEFAULT 0 COMMENT '已抵扣（同币种 AGGREGATED 出款中扣）',
  collected_minor BIGINT NOT NULL DEFAULT 0 COMMENT '已收',
  status VARCHAR(24) NOT NULL COMMENT 'OPEN / PARTIAL / SETTLED / WRITTEN_OFF',
  due_date DATE NOT NULL COMMENT '到期日',
  overdue_days INT NOT NULL DEFAULT 0 COMMENT '逾期天数（任务每日刷新）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_fee_receivable_1 (receivable_no),
  UNIQUE KEY uk_pay_fee_receivable_2 (pay_merchant_id,period_from,period_to),
  KEY idx_pay_fee_receivable_1 (store_id,status),
  KEY idx_pay_fee_receivable_2 (status,due_date),
  CONSTRAINT ck_pay_fee_receivable_1 CHECK (offset_minor + collected_minor <= amount_minor)
) COMMENT='平台费应收（DIRECT 路径：钱不经过平台，平台费事后向商家收）';


-- ════════ 现有表改造（现有表保留 tenant_no 与原主键，不动） ════════

ALTER TABLE sys_pay_channel ADD COLUMN callback_style VARCHAR(16) NOT NULL DEFAULT 'PATH' COMMENT 'PATH 子路径 / QUERY 参数：渠道是否接受带路径变量的回调地址';
ALTER TABLE sys_pay_channel ADD COLUMN sandbox_host VARCHAR(128) NULL COMMENT '沙箱主机（不含密钥）';
ALTER TABLE sys_pay_channel ADD COLUMN prod_host VARCHAR(128) NULL COMMENT '正式主机';
ALTER TABLE sys_pay_channel ADD COLUMN supports_statement TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否有对账单下载接口（无则退化逐笔查单）';

ALTER TABLE sys_pay_channel_market ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED；**语义改为无行 = 不可用**，TEST 通道补显式行';

ALTER TABLE stl_payment ADD COLUMN pay_order_id BIGINT NULL COMMENT '所属支付单 pay_order.id（回填后对新数据必填）';
ALTER TABLE stl_payment ADD COLUMN pay_refund_id BIGINT NULL COMMENT 'REFUND 方向：所属退款单 pay_refund.id';
ALTER TABLE stl_payment ADD COLUMN pay_merchant_id BIGINT NULL COMMENT '冻结的收款商户 pay_merchant.id（修 R08）';
ALTER TABLE stl_payment ADD COLUMN sub_merchant_id VARCHAR(64) NULL COMMENT '冻结的渠道子商户号；退款从这里取（修 R08）';
ALTER TABLE stl_payment ADD COLUMN funds_path VARCHAR(24) NULL COMMENT '冻结的资金路径';
ALTER TABLE stl_payment ADD COLUMN market VARCHAR(8) NULL COMMENT '市场';
ALTER TABLE stl_payment ADD COLUMN sec_key_version INT NULL COMMENT '冻结的密钥版本号';
ALTER TABLE stl_payment ADD COLUMN channel_amount_minor BIGINT NULL COMMENT '通道回执金额；**不再覆盖 amount_minor**（修 R20）';
ALTER TABLE stl_payment ADD COLUMN channel_currency CHAR(3) NULL COMMENT '通道回执币种';
ALTER TABLE stl_payment ADD UNIQUE KEY uk_stl_payment_pay_1 (out_trade_no);  -- 先查存量重复
ALTER TABLE stl_payment ADD KEY idx_stl_payment_pay_1 (pay_order_id);
ALTER TABLE stl_payment ADD KEY idx_stl_payment_pay_2 (pay_merchant_id,succeeded_at);

ALTER TABLE stl_recon_diff ADD COLUMN dedup_key VARCHAR(160) NULL COMMENT '去重键（不含账单日），同一异常不每天新建（修 R19）';
ALTER TABLE stl_recon_diff ADD COLUMN note VARCHAR(512) NULL COMMENT '说明；resolution 只放处置结论';
ALTER TABLE stl_recon_diff ADD COLUMN pay_merchant_id BIGINT NULL COMMENT '收款商户 pay_merchant.id';
ALTER TABLE stl_recon_diff ADD COLUMN stl_channel_statement_id BIGINT NULL COMMENT '来源对账单 stl_channel_statement.id';
ALTER TABLE stl_recon_diff ADD UNIQUE KEY uk_stl_recon_diff_pay_1 (dedup_key);  -- 先查存量重复

ALTER TABLE stl_bank_flow ADD COLUMN currency CHAR(3) NULL COMMENT '币种（多市场）';
ALTER TABLE stl_bank_flow ADD COLUMN own_account_ref VARCHAR(64) NULL COMMENT '本方账户引用';

ALTER TABLE stl_bill ADD COLUMN market VARCHAR(8) NULL COMMENT '市场';
ALTER TABLE stl_bill ADD COLUMN pay_order_id BIGINT NULL COMMENT '来源支付单 pay_order.id';

