-- 【设计稿，未落库，勿手改】由 python3 scripts/gen-invoice-service-docs.py 生成
-- 目标状态 DDL（库 ai_shop_invoice）；实际迁移为 db/invoice/V6+ 的增量脚本，不改已应用的 V1–V5。

CREATE TABLE ivc_region (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  region_code VARCHAR(8) NOT NULL COMMENT '地区码：TW / AE',
  country_code CHAR(2) NOT NULL COMMENT 'ISO 3166 国家码',
  regime_code VARCHAR(16) NOT NULL COMMENT '税制码：TW_EGUI / AE_EINVOICE',
  currency CHAR(3) NOT NULL COMMENT '币种',
  amount_scale TINYINT NOT NULL COMMENT '金额小数位（TWD=0）',
  timezone VARCHAR(32) NOT NULL COMMENT '期别与票面时间所用时区，如 Asia/Taipei',
  period_rule VARCHAR(16) NOT NULL COMMENT 'BIMONTHLY / MONTHLY / NONE',
  rule_version VARCHAR(16) NOT NULL COMMENT '税制规则版本，如 MIG4.0',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / DISABLED',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_region (region_code)
) COMMENT='地区与税制。一行对应一个 InvoiceRegime 实现；有行无实现启动失败';

CREATE TABLE ivc_provider (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  provider_code VARCHAR(16) NOT NULL COMMENT 'CETUSTEK / ECPAY / TURNKEY / STUB',
  name VARCHAR(64) NOT NULL COMMENT '名称',
  provider_type VARCHAR(24) NOT NULL COMMENT 'VALUE_ADDED_CENTER / GOV_DIRECT / ASP / TEST',
  protocol VARCHAR(16) NOT NULL COMMENT 'SOAP / JSON_AES / FILE',
  sandbox_endpoint VARCHAR(255) NULL COMMENT '测试地址（Turnkey 为目录根）；不含凭据',
  production_endpoint VARCHAR(255) NULL COMMENT '正式地址',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / DISABLED（运营开关）',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  updated_by VARCHAR(64) NULL COMMENT '更新人',
  UNIQUE KEY uk_ivc_provider (provider_code)
) COMMENT='开票供应商（加值中心 / 直连 / 测试）';

CREATE TABLE ivc_provider_region (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  provider_id BIGINT NOT NULL COMMENT '→ ivc_provider.id',
  region_id BIGINT NOT NULL COMMENT '→ ivc_region.id',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / DISABLED',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  updated_by VARCHAR(64) NULL COMMENT '更新人',
  UNIQUE KEY uk_ivc_provider_region (provider_id, region_id)
) COMMENT='供应商 × 地区开通关系。无行 = 该供应商在该地区不可用';

CREATE TABLE ivc_provider_product (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  provider_id BIGINT NOT NULL COMMENT '→ ivc_provider.id',
  region_id BIGINT NOT NULL COMMENT '→ ivc_region.id',
  doc_type VARCHAR(8) NOT NULL COMMENT 'B2C / B2B',
  numbering_mode VARCHAR(24) NOT NULL COMMENT 'LOCAL_RANGE / PROVIDER_RANGE / PROVIDER_ONLINE / LOCAL_SEQUENCE',
  report_mode VARCHAR(16) NOT NULL COMMENT 'ASYNC_UPLOAD / SYNC_ON_ISSUE / FILE_DROP',
  supports_void TINYINT NOT NULL DEFAULT 0 COMMENT '支持作废',
  supports_allowance TINYINT NOT NULL DEFAULT 0 COMMENT '支持折让',
  supports_allowance_void TINYINT NOT NULL DEFAULT 0 COMMENT '支持折让作废',
  supports_query TINYINT NOT NULL DEFAULT 0 COMMENT '支持查单',
  supports_verify TINYINT NOT NULL DEFAULT 0 COMMENT '支持账户核验（无则录入即启用）',
  carrier_types VARCHAR(128) NULL COMMENT '支持的载具类别，逗号分隔',
  print_mode VARCHAR(16) NOT NULL COMMENT 'LOCAL（打印中心）/ PROVIDER_URL（供应商列印网址）',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '运营开关',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  updated_by VARCHAR(64) NULL COMMENT '更新人',
  UNIQUE KEY uk_ivc_provider_product (provider_id, region_id, doc_type)
) COMMENT='供应商能力（供应商 × 地区 × 票种）。启动时与代码实现的能力接口比对，不一致拒绝启用';

CREATE TABLE ivc_provider_credential_field (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  provider_id BIGINT NOT NULL COMMENT '→ ivc_provider.id',
  field_code VARCHAR(32) NOT NULL COMMENT 'office_id / pos_no / pos_id；merchant_id / hash_key / hash_iv；sender_id',
  label_key VARCHAR(64) NOT NULL COMMENT '多语文案键',
  is_secret TINYINT NOT NULL COMMENT '1=进 secret 模块、只回掩码；0=明文',
  is_account_code TINYINT NOT NULL DEFAULT 0 COMMENT '1=该字段即 provider_account_code（每家恰好一个）',
  required TINYINT NOT NULL DEFAULT 1 COMMENT '必填',
  pattern VARCHAR(128) NULL COMMENT '格式正则',
  max_length INT NOT NULL COMMENT '最大长度',
  sort_no INT NOT NULL COMMENT '表单顺序',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_credential_field (provider_id, field_code)
) COMMENT='供应商凭据字段定义：前端据此渲染录入表单，后端据此校验';

CREATE TABLE ivc_seller (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  seller_no VARCHAR(32) NOT NULL COMMENT '营业人业务号',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  entity_no VARCHAR(64) NOT NULL COMMENT '主体业务号（核心系统）',
  region_id BIGINT NOT NULL COMMENT '→ ivc_region.id',
  tax_id VARCHAR(32) NOT NULL COMMENT '统编 / TRN，由税制校验',
  legal_name VARCHAR(128) NOT NULL COMMENT '营业人名称（快照）',
  address VARCHAR(256) NULL COMMENT '营业地址（快照）',
  auto_issue TINYINT NOT NULL DEFAULT 0 COMMENT '付款成功后自动开立',
  member_carrier_type VARCHAR(16) NULL COMMENT '会员载具类别号（台湾）',
  qr_key_id BIGINT NULL COMMENT 'QR 加密金钥 secret 引用（财政部按统编核发）',
  qr_key_source VARCHAR(16) NULL COMMENT 'MERCHANT / PLATFORM / PROVIDER（鲸跃核验回传）',
  qr_key_fingerprint CHAR(16) NULL COMMENT '金钥指纹，供界面显示',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / SUSPENDED',
  created_by VARCHAR(64) NULL COMMENT '创建人',
  updated_by VARCHAR(64) NULL COMMENT '更新人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_seller_no (seller_no),
  UNIQUE KEY uk_ivc_seller (tenant_no, entity_no, region_id)
) COMMENT='营业人：主体在某地区开通发票（取代 ivc_regime_profile 的营业人部分）';

CREATE TABLE ivc_provider_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_no VARCHAR(32) NOT NULL COMMENT '账户业务号',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  seller_id BIGINT NOT NULL COMMENT '→ ivc_seller.id',
  provider_id BIGINT NOT NULL COMMENT '→ ivc_provider.id',
  environment VARCHAR(16) NOT NULL COMMENT 'SANDBOX / PRODUCTION',
  provider_account_code VARCHAR(64) NOT NULL COMMENT '鲸跃 officeid / 绿界 MerchantID / Turnkey 传送者代号',
  managed_by VARCHAR(16) NOT NULL COMMENT 'MERCHANT（商家自填）/ PLATFORM（平台代维护）',
  credential_version INT NULL COMMENT '当前生效凭据版本（→ ivc_account_credential.version）',
  status VARCHAR(16) NOT NULL COMMENT 'ONBOARDING / ACTIVE / ISSUE_STOPPED / SUSPENDED / CLOSED',
  verified_at DATETIME(3) NULL COMMENT '最近一次核验成功时间',
  verified_snapshot LONGTEXT NULL COMMENT '核验时供应商侧营业人资料（JSON）',
  last_verify_error VARCHAR(256) NULL COMMENT '最近一次核验失败原因（供应商原文代码）',
  created_via VARCHAR(8) NOT NULL COMMENT 'BIZ / OPS',
  active_key VARCHAR(80) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN CONCAT(seller_id,':',provider_id,':',environment) END) VIRTUAL,
  created_by VARCHAR(64) NULL COMMENT '创建人',
  updated_by VARCHAR(64) NULL COMMENT '更新人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_account_no (account_no),
  UNIQUE KEY uk_ivc_account_active (active_key),
  KEY idx_ivc_account_seller (seller_id, status),
  CONSTRAINT ck_ivc_account_snapshot CHECK (verified_snapshot IS NULL OR JSON_VALID(verified_snapshot))
) COMMENT='营业人在某供应商的账户（对照 pay_merchant）。凭据不在本表';

CREATE TABLE ivc_account_credential (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL COMMENT '→ ivc_provider_account.id',
  version INT NOT NULL COMMENT '账户内递增',
  sec_key_id BIGINT NOT NULL COMMENT 'secret 模块引用（PLATFORM 命名空间，ownerType INVOICE_PROVIDER）',
  fingerprint CHAR(16) NOT NULL COMMENT '秘密字段规范化后 SHA-256 前 16 位',
  masks LONGTEXT NOT NULL COMMENT '各秘密字段掩码（JSON），仅供展示',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING / CURRENT / RETIRED / FAILED',
  verify_error VARCHAR(256) NULL COMMENT '核验失败原因',
  created_via VARCHAR(8) NOT NULL COMMENT 'BIZ / OPS',
  created_by VARCHAR(64) NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  effective_at DATETIME(3) NULL COMMENT '成为 CURRENT 的时间',
  retired_at DATETIME(3) NULL COMMENT '退役时间',
  current_key BIGINT GENERATED ALWAYS AS (CASE WHEN status='CURRENT' THEN account_id END) VIRTUAL,
  UNIQUE KEY uk_ivc_credential_version (account_id, version),
  UNIQUE KEY uk_ivc_credential_current (current_key),
  CONSTRAINT ck_ivc_credential_masks CHECK (JSON_VALID(masks))
) COMMENT='凭据版本。秘密字段整组封存在 secret 模块，本表只存引用、指纹、掩码';

CREATE TABLE ivc_account_binding (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  seller_id BIGINT NOT NULL COMMENT '→ ivc_seller.id',
  store_no VARCHAR(64) NOT NULL COMMENT '门店业务号；''*'' = 营业人级默认',
  account_id BIGINT NOT NULL COMMENT '→ ivc_provider_account.id',
  effective_from DATETIME(3) NOT NULL COMMENT '生效时间',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / RETIRED',
  active_key VARCHAR(80) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN CONCAT(seller_id,':',store_no) END) VIRTUAL,
  created_by VARCHAR(64) NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_binding_active (active_key),
  KEY idx_ivc_binding_account (account_id)
) COMMENT='门店 → 账户绑定；store_no=''*'' 为营业人级默认。只影响之后的新票';

CREATE TABLE ivc_account_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL COMMENT '→ ivc_provider_account.id',
  event_type VARCHAR(32) NOT NULL COMMENT 'CREATED / CREDENTIAL_SUBMITTED / CREDENTIAL_EFFECTIVE / CREDENTIAL_FAILED / VERIFIED / VERIFY_FAILED / STATUS_CHANGED / MANAGEMENT_CHANGED / BINDING_CHANGED / RANGE_FETCHED',
  operator_type VARCHAR(8) NOT NULL COMMENT 'BIZ / OPS / SYSTEM',
  operator_no VARCHAR(64) NULL COMMENT '操作人',
  detail LONGTEXT NULL COMMENT '掩码后的前后差异（JSON），不含凭据',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  KEY idx_ivc_account_event (account_id, created_at),
  CONSTRAINT ck_ivc_account_event_detail CHECK (detail IS NULL OR JSON_VALID(detail))
) COMMENT='账户事件，只增不改（不设审核，靠它追溯）';

CREATE TABLE ivc_number_range (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  range_no VARCHAR(32) NOT NULL COMMENT '号段业务号',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  entity_no VARCHAR(64) NOT NULL COMMENT '主体',
  regime_code VARCHAR(16) NOT NULL COMMENT '税制',
  account_id BIGINT NOT NULL COMMENT '【新】所属账户 → ivc_provider_account.id',
  store_no VARCHAR(64) NOT NULL COMMENT '''*'' = 各店共用',
  period_code VARCHAR(8) NOT NULL COMMENT '期别，如 11512',
  track VARCHAR(4) NOT NULL COMMENT '字轨',
  start_no INT NOT NULL COMMENT '起号',
  end_no INT NOT NULL COMMENT '迄号',
  next_no INT NOT NULL COMMENT '下一个可用号；> end_no 即用尽',
  low_water INT NOT NULL DEFAULT 0 COMMENT '【新】剩余低于此值提醒 / 自动领号',
  allocation_owner VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 商家登记 / PROVIDER 供应商配给（V5）',
  apply_id VARCHAR(64) NULL COMMENT '供应商配号申请号（V5）',
  invoice_type VARCHAR(4) NULL COMMENT '供应商回传发票种类（V5）',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / EXHAUSTED',
  created_by VARCHAR(64) NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_number_range_no (tenant_no, range_no),
  UNIQUE KEY uk_ivc_number_range (tenant_no, entity_no, regime_code, period_code, track, start_no),
  KEY idx_ivc_number_range_pick (account_id, period_code, status, store_no),
  CONSTRAINT ck_ivc_number_range_bounds CHECK (start_no >= 0 AND start_no <= end_no AND end_no <= 99999999 AND next_no >= start_no AND next_no <= end_no + 1)
) COMMENT='字轨号段；取号与开立同一事务、不回收';

CREATE TABLE ivc_invoice (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  invoice_no VARCHAR(32) NOT NULL COMMENT '内部单号',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  entity_no VARCHAR(64) NOT NULL COMMENT '主体',
  store_no VARCHAR(64) NOT NULL COMMENT '门店',
  seller_id BIGINT NOT NULL COMMENT '【新】→ ivc_seller.id',
  account_id BIGINT NOT NULL COMMENT '【新】冻结的开票账户 → ivc_provider_account.id',
  region_code VARCHAR(8) NOT NULL COMMENT '【新】地区（快照）',
  regime_code VARCHAR(16) NOT NULL COMMENT '税制',
  provider_code VARCHAR(16) NOT NULL COMMENT '【新】供应商（快照）',
  numbering_mode VARCHAR(24) NOT NULL COMMENT '【新】编号方式（快照）',
  industry_code VARCHAR(32) NOT NULL COMMENT '行业',
  source_type VARCHAR(32) NOT NULL COMMENT '来源类型',
  source_no VARCHAR(64) NOT NULL COMMENT '来源单号',
  active_source_key VARCHAR(128) NULL COMMENT '同来源同时只有一张有效票；作废 / 开立失败置空',
  fiscal_number VARCHAR(32) NULL COMMENT '法定号码；在线配号回写前为空【改为可空】',
  range_no VARCHAR(32) NULL COMMENT '号段；在线配号为空【改为可空】',
  period_code VARCHAR(8) NULL COMMENT '期别',
  random_code VARCHAR(8) NULL COMMENT '随机码',
  provider_order_no VARCHAR(64) NULL COMMENT '【新】供应商侧幂等身份（绿界 RelateNumber；鲸跃 OrderId）',
  provider_ref VARCHAR(128) NULL COMMENT '【新】供应商回执号',
  issue_status VARCHAR(16) NOT NULL COMMENT '【新】PENDING / UNKNOWN / ISSUED / REJECTED',
  report_status VARCHAR(16) NOT NULL COMMENT '【新】PENDING / REPORTED / REJECTED / NOT_REQUIRED',
  seller_tax_id VARCHAR(32) NOT NULL COMMENT '卖方统编（冻结）',
  seller_name VARCHAR(128) NOT NULL COMMENT '卖方名称（冻结）',
  issued_at DATETIME(3) NULL COMMENT '开立时间（UTC）',
  buyer_type VARCHAR(8) NOT NULL COMMENT 'B2C / B2B',
  buyer_user_no VARCHAR(64) NULL COMMENT '平台买家用户号',
  buyer_tax_id VARCHAR(32) NULL COMMENT '买方统编',
  buyer_name VARCHAR(128) NULL COMMENT '买方名称',
  carrier_type VARCHAR(16) NULL COMMENT '载具类别',
  carrier_id VARCHAR(64) NULL COMMENT '载具号码',
  donate_code VARCHAR(16) NULL COMMENT '爱心码',
  print_mark CHAR(1) NOT NULL COMMENT 'Y=印证明联',
  currency CHAR(3) NOT NULL COMMENT '币种',
  sales_minor BIGINT NOT NULL COMMENT '应税销售额',
  zero_tax_sales_minor BIGINT NOT NULL DEFAULT 0 COMMENT '零税率销售额',
  free_tax_sales_minor BIGINT NOT NULL DEFAULT 0 COMMENT '免税销售额',
  tax_minor BIGINT NOT NULL COMMENT '税额',
  total_minor BIGINT NOT NULL COMMENT '总计',
  allowed_minor BIGINT NOT NULL DEFAULT 0 COMMENT '累计折让',
  tax_type VARCHAR(16) NOT NULL COMMENT 'TAXABLE / ZERO / EXEMPT / MIXED',
  tax_rate_bp INT NOT NULL COMMENT '税率（基点）',
  status VARCHAR(16) NOT NULL COMMENT 'ISSUED / VOIDED（法律状态）',
  void_reason VARCHAR(256) NULL COMMENT '作废原因',
  voided_at DATETIME(3) NULL COMMENT '作废时间',
  voided_by VARCHAR(64) NULL COMMENT '作废人',
  proof_snapshot LONGTEXT NULL COMMENT '证明联冻结内容；在线配号回写前为空',
  proof_snapshot_hash CHAR(64) NULL COMMENT '哈希',
  detail_snapshot LONGTEXT NULL COMMENT '交易明细冻结内容',
  detail_snapshot_hash CHAR(64) NULL COMMENT '哈希',
  created_by VARCHAR(64) NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_invoice_no (tenant_no, invoice_no),
  UNIQUE KEY uk_ivc_invoice_fiscal (tenant_no, region_code, period_code, fiscal_number),
  UNIQUE KEY uk_ivc_invoice_active_source (tenant_no, entity_no, active_source_key),
  UNIQUE KEY uk_ivc_invoice_provider_order (account_id, provider_order_no),
  KEY idx_ivc_invoice_store (tenant_no, entity_no, store_no, period_code, created_at),
  KEY idx_ivc_invoice_buyer (tenant_no, buyer_user_no, issued_at),
  KEY idx_ivc_invoice_recovery (issue_status, updated_at),
  CONSTRAINT ck_ivc_invoice_proof_json CHECK (proof_snapshot IS NULL OR JSON_VALID(proof_snapshot)),
  CONSTRAINT ck_ivc_invoice_detail_json CHECK (detail_snapshot IS NULL OR JSON_VALID(detail_snapshot)),
  CONSTRAINT ck_ivc_invoice_issued CHECK (issue_status <> 'ISSUED' OR (fiscal_number IS NOT NULL AND proof_snapshot IS NOT NULL)),
  CONSTRAINT ck_ivc_invoice_amounts CHECK (total_minor >= 0 AND allowed_minor >= 0 AND allowed_minor <= total_minor AND sales_minor + zero_tax_sales_minor + free_tax_sales_minor + tax_minor = total_minor)
) COMMENT='发票；明细、金额、路由在开立时冻结。【新】列为本次新增';

CREATE TABLE ivc_invoice_line (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  invoice_no VARCHAR(32) NOT NULL COMMENT '发票',
  line_no INT NOT NULL COMMENT '行号',
  source_line_ref VARCHAR(64) NOT NULL COMMENT '来源行',
  name VARCHAR(256) NOT NULL COMMENT '品名',
  quantity DECIMAL(18,4) NOT NULL COMMENT '数量',
  unit VARCHAR(16) NULL COMMENT '单位',
  unit_price_minor BIGINT NOT NULL COMMENT '单价',
  amount_minor BIGINT NOT NULL COMMENT '金额',
  tax_type VARCHAR(16) NOT NULL COMMENT '课税别',
  UNIQUE KEY uk_ivc_invoice_line (tenant_no, invoice_no, line_no)
) COMMENT='发票明细';

CREATE TABLE ivc_allowance (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  allowance_no VARCHAR(32) NOT NULL COMMENT '内部单号',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  entity_no VARCHAR(64) NOT NULL COMMENT '主体',
  store_no VARCHAR(64) NOT NULL COMMENT '门店',
  invoice_no VARCHAR(32) NOT NULL COMMENT '原发票',
  account_id BIGINT NOT NULL COMMENT '【新】原票账户（冗余便于任务）',
  fiscal_allowance_number VARCHAR(16) NULL COMMENT '对外折让单号 = 号码 + 两位序号（V5）',
  provider_allowance_no VARCHAR(64) NULL COMMENT '【新】供应商折让号（绿界）',
  amount_minor BIGINT NOT NULL COMMENT '含税折让金额',
  tax_minor BIGINT NOT NULL COMMENT '税额',
  reason VARCHAR(256) NOT NULL COMMENT '原因',
  issue_status VARCHAR(16) NOT NULL COMMENT '【新】PENDING / UNKNOWN / ISSUED / REJECTED',
  status VARCHAR(16) NOT NULL COMMENT '【新】ACTIVE / VOIDED',
  voided_at DATETIME(3) NULL COMMENT '【新】作废时间',
  print_snapshot LONGTEXT NULL COMMENT '折让证明单冻结内容',
  print_snapshot_hash CHAR(64) NULL COMMENT '哈希',
  created_by VARCHAR(64) NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  UNIQUE KEY uk_ivc_allowance_no (tenant_no, allowance_no),
  KEY idx_ivc_allowance_invoice (tenant_no, invoice_no),
  CONSTRAINT ck_ivc_allowance_amount CHECK (amount_minor > 0 AND tax_minor >= 0 AND tax_minor <= amount_minor),
  CONSTRAINT ck_ivc_allowance_print_json CHECK (print_snapshot IS NULL OR JSON_VALID(print_snapshot))
) COMMENT='折让；账户取自原票';

CREATE TABLE ivc_buyer_preference (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  entity_no VARCHAR(64) NOT NULL COMMENT '主体',
  source_type VARCHAR(32) NOT NULL COMMENT '来源类型',
  source_no VARCHAR(64) NOT NULL COMMENT '来源单号',
  buyer_tax_id VARCHAR(32) NULL COMMENT '买方统编',
  buyer_name VARCHAR(128) NULL COMMENT '买方名称',
  carrier_type VARCHAR(16) NULL COMMENT '载具类别',
  carrier_id VARCHAR(64) NULL COMMENT '载具号码',
  donate_code VARCHAR(16) NULL COMMENT '爱心码',
  created_by VARCHAR(64) NULL COMMENT '创建人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_buyer_preference (tenant_no, entity_no, source_type, source_no)
) COMMENT='付款前预存的买受人选择；以最后一次为准';

CREATE TABLE ivc_user_carrier (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  carrier_no VARCHAR(32) NOT NULL COMMENT '业务号',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  user_no VARCHAR(64) NOT NULL COMMENT '买家',
  region_code VARCHAR(8) NOT NULL DEFAULT 'TW' COMMENT '【新】载具所属地区',
  carrier_type VARCHAR(16) NOT NULL COMMENT '3J0002 手机条码 / CQ0001 自然人凭证',
  carrier_id VARCHAR(64) NOT NULL COMMENT '载具号码',
  label VARCHAR(32) NULL COMMENT '买家自起名',
  is_default TINYINT NOT NULL DEFAULT 0 COMMENT '默认',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_user_carrier_no (tenant_no, carrier_no),
  UNIQUE KEY uk_ivc_user_carrier (tenant_no, user_no, region_code, carrier_type, carrier_id)
) COMMENT='买家常用载具（平台级，跨主体）';

CREATE TABLE ivc_transmission (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  transmission_no VARCHAR(32) NOT NULL COMMENT '业务号',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  entity_no VARCHAR(64) NOT NULL COMMENT '主体',
  account_id BIGINT NOT NULL COMMENT '【新】原票账户',
  regime_code VARCHAR(16) NOT NULL COMMENT '税制',
  provider_code VARCHAR(16) NOT NULL COMMENT '供应商（原 gateway_code）',
  operation VARCHAR(16) NOT NULL COMMENT 'ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID（原 message_type）',
  ref_no VARCHAR(32) NOT NULL COMMENT 'invoice_no / allowance_no',
  payload LONGTEXT NOT NULL COMMENT '冻结报文，补传原样再发',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING / SENDING / SENT / FAILED',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试次数',
  next_attempt_at DATETIME(3) NOT NULL COMMENT '下次尝试',
  last_error VARCHAR(512) NULL COMMENT '最近错误',
  provider_ref VARCHAR(128) NULL COMMENT '供应商回执号',
  deadline_at DATETIME(3) NULL COMMENT '【新】法定上报期限（台湾 B2C 开立后 48 小时）',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_transmission_no (tenant_no, transmission_no),
  UNIQUE KEY uk_ivc_transmission_ref (tenant_no, operation, ref_no),
  KEY idx_ivc_transmission_due (status, next_attempt_at),
  CONSTRAINT ck_ivc_transmission_payload CHECK (JSON_VALID(payload))
) COMMENT='异步上报任务；重试只在这里';

CREATE TABLE ivc_api_request_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  request_no VARCHAR(32) NOT NULL COMMENT '业务号',
  trace_no VARCHAR(64) NULL COMMENT '追踪号',
  caller_type VARCHAR(8) NOT NULL COMMENT 'BIZ / MP / OPS / OPEN / INTERNAL',
  caller_ref VARCHAR(64) NULL COMMENT '主体 / 应用 / 操作人',
  method VARCHAR(8) NOT NULL COMMENT 'HTTP 方法',
  path VARCHAR(255) NOT NULL COMMENT '路径模板',
  idempotency_key VARCHAR(64) NULL COMMENT '幂等键',
  request_masked LONGTEXT NULL COMMENT '脱敏请求',
  response_code INT NULL COMMENT '业务码',
  elapsed_ms INT NULL COMMENT '耗时',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  UNIQUE KEY uk_ivc_api_request_no (request_no),
  KEY idx_ivc_api_request_idem (caller_type, caller_ref, idempotency_key)
) COMMENT='调用方进来的请求留痕（对照 pay_api_request_log）';

CREATE TABLE ivc_provider_call_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  call_no VARCHAR(32) NOT NULL COMMENT '业务号',
  trace_no VARCHAR(64) NULL COMMENT '追踪号',
  account_id BIGINT NOT NULL COMMENT '账户',
  provider_code VARCHAR(16) NOT NULL COMMENT '供应商',
  operation VARCHAR(24) NOT NULL COMMENT 'ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID / QUERY / RANGE / VERIFY',
  ref_type VARCHAR(16) NULL COMMENT 'INVOICE / ALLOWANCE / ACCOUNT / RANGE',
  ref_no VARCHAR(64) NULL COMMENT '业务号',
  attempt INT NOT NULL DEFAULT 1 COMMENT '第几次',
  request_masked LONGTEXT NULL COMMENT '脱敏请求',
  response_masked LONGTEXT NULL COMMENT '脱敏响应',
  raw_payload_id BIGINT NULL COMMENT '→ ivc_raw_payload.id（加密原文）',
  http_status INT NULL COMMENT 'HTTP 状态',
  elapsed_ms INT NULL COMMENT '耗时',
  outcome VARCHAR(16) NOT NULL COMMENT 'ACCEPTED / REJECTED / UNKNOWN',
  provider_result_code VARCHAR(64) NULL COMMENT '供应商原文结果码（S0 / RtnCode …）',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  UNIQUE KEY uk_ivc_call_no (call_no),
  KEY idx_ivc_call_ref (ref_type, ref_no),
  KEY idx_ivc_call_account (account_id, created_at)
) COMMENT='对供应商的每一次调用';

CREATE TABLE ivc_provider_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  receipt_no VARCHAR(32) NOT NULL COMMENT '业务号',
  account_id BIGINT NULL COMMENT '账户（验签前可空）',
  provider_code VARCHAR(16) NOT NULL COMMENT '供应商',
  kind VARCHAR(16) NOT NULL COMMENT 'CALLBACK / FILE_RECEIPT / PLATFORM_RESULT',
  ref_no VARCHAR(64) NULL COMMENT '关联业务号',
  raw_payload_id BIGINT NOT NULL COMMENT '→ ivc_raw_payload.id',
  raw_sha256 CHAR(64) NOT NULL COMMENT '原文哈希',
  verify_result VARCHAR(16) NULL COMMENT 'PASSED / FAILED / SKIPPED',
  outcome VARCHAR(16) NULL COMMENT 'APPLIED / IGNORED / FAILED',
  replay_of BIGINT NULL COMMENT '回放来源 → ivc_provider_receipt.id',
  received_at DATETIME(3) NOT NULL COMMENT '收到时间',
  handled_at DATETIME(3) NULL COMMENT '处理完成时间',
  UNIQUE KEY uk_ivc_receipt_no (receipt_no),
  KEY idx_ivc_receipt_ref (provider_code, ref_no)
) COMMENT='外部结果进来的唯一记录（回调 / Turnkey 回执文件 / 平台结果），可回放';

CREATE TABLE ivc_raw_payload (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cipher LONGBLOB NOT NULL COMMENT '加密原文',
  sha256 CHAR(64) NOT NULL COMMENT '原文哈希',
  expire_at DATETIME(3) NOT NULL COMMENT '到期清理时间',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  KEY idx_ivc_raw_expire (expire_at)
) COMMENT='原文单独存（信封加密），按合规期限清理';

CREATE TABLE ivc_biz_notify (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  notify_no VARCHAR(32) NOT NULL COMMENT '业务号',
  event_type VARCHAR(48) NOT NULL COMMENT 'InvoiceIssued / InvoiceVoided / BusinessPrintFactReady …',
  payload LONGTEXT NOT NULL COMMENT '事件内容（JSON）',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING / SENT / FAILED',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试',
  next_attempt_at DATETIME(3) NOT NULL COMMENT '下次尝试',
  last_error VARCHAR(512) NULL COMMENT '最近错误',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
  UNIQUE KEY uk_ivc_notify_no (notify_no),
  KEY idx_ivc_notify_due (status, next_attempt_at),
  CONSTRAINT ck_ivc_notify_payload CHECK (JSON_VALID(payload))
) COMMENT='独立形态下发给主应用的事件（开立、作废、打印事实）；内嵌形态走平台 Outbox';

CREATE TABLE ivc_recon_diff (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  recon_date DATE NOT NULL COMMENT '对账日',
  account_id BIGINT NOT NULL COMMENT '账户',
  ref_type VARCHAR(16) NOT NULL COMMENT 'INVOICE / ALLOWANCE',
  ref_no VARCHAR(64) NOT NULL COMMENT '业务号',
  local_state VARCHAR(32) NULL COMMENT '本地状态',
  provider_state VARCHAR(32) NULL COMMENT '供应商状态',
  diff_type VARCHAR(24) NOT NULL COMMENT 'MISSING_REMOTE / MISSING_LOCAL / STATE_MISMATCH / AMOUNT_MISMATCH',
  resolution VARCHAR(16) NULL COMMENT 'OPEN / FIXED / ACCEPTED',
  resolved_by VARCHAR(64) NULL COMMENT '处理人',
  resolved_at DATETIME(3) NULL COMMENT '处理时间',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
  UNIQUE KEY uk_ivc_recon_diff (recon_date, account_id, ref_type, ref_no)
) COMMENT='本地与供应商 / 税务平台状态的差异';
