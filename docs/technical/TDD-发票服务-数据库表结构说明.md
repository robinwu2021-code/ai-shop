# TDD-发票服务-数据库表结构说明

状态：草稿 · 设计稿未落库（`DESIGN_ONLY_NOT_APPLIED`）
> 由 `python3 scripts/gen-invoice-service-docs.py` 生成 · 真源 `docs/technical/design/schema/invoice-service-database.json` · **勿手改**
> 设计依据：[TDD-发票服务-总体设计](./TDD-发票服务-总体设计.md) §5、§13 · DDL 草案：[invoice-service.sql](./design/schema/invoice-service.sql)

## 一、总览

库 `ai_shop_invoice`，Flyway `db/invoice`（历史表 `ivc_flyway_history`）。共 23 张表：新增 16、改 5、沿用 2；删除 `ivc_regime_profile`（拆为 ivc_seller + ivc_provider_account + ivc_account_credential + ivc_account_binding；迁移验证后删除）。

约定：

- 主键 id BIGINT AUTO_INCREMENT；库内关联用 <表>_id
- 引用核心系统只存业务键（tenant_no / entity_no / store_no / source_no）+ 快照
- 时间 DATETIME(3) UTC；快照与报文用 LONGTEXT + CHECK(JSON_VALID)（JSON 类型会改键序，哈希对不上）
- 凭据与金钥只存 secret 模块引用，任何表无明文

## 二、表清单

| # | 分组 | 表 | 动作 | 用途 | 列数 |
|---|---|---|---|---|---|
| 1 | A 主数据 | [`ivc_region`](#ivc_region) | 新 | 地区与税制。一行对应一个 InvoiceRegime 实现；有行无实现启动失败 | 12 |
| 2 | A 主数据 | [`ivc_provider`](#ivc_provider) | 新 | 开票供应商（加值中心 / 直连 / 测试） | 11 |
| 3 | A 主数据 | [`ivc_provider_region`](#ivc_provider_region) | 新 | 供应商 × 地区开通关系。无行 = 该供应商在该地区不可用 | 7 |
| 4 | A 主数据 | [`ivc_provider_product`](#ivc_provider_product) | 新 | 供应商能力（供应商 × 地区 × 票种）。启动时与代码实现的能力接口比对，不一致拒绝启用 | 17 |
| 5 | B 营业人与账户 | [`ivc_provider_credential_field`](#ivc_provider_credential_field) | 新 | 供应商凭据字段定义：前端据此渲染录入表单，后端据此校验 | 12 |
| 6 | B 营业人与账户 | [`ivc_seller`](#ivc_seller) | 新 | 营业人：主体在某地区开通发票（取代 ivc_regime_profile 的营业人部分） | 18 |
| 7 | B 营业人与账户 | [`ivc_provider_account`](#ivc_provider_account) | 新 | 营业人在某供应商的账户（对照 pay_merchant）。凭据不在本表 | 19 |
| 8 | B 营业人与账户 | [`ivc_account_credential`](#ivc_account_credential) | 新 | 凭据版本。秘密字段整组封存在 secret 模块，本表只存引用、指纹、掩码 | 14 |
| 9 | B 营业人与账户 | [`ivc_account_binding`](#ivc_account_binding) | 新 | 门店 → 账户绑定；store_no='*' 为营业人级默认。只影响之后的新票 | 10 |
| 10 | B 营业人与账户 | [`ivc_account_event`](#ivc_account_event) | 新 | 账户事件，只增不改（不设审核，靠它追溯） | 7 |
| 11 | C 号码 | [`ivc_number_range`](#ivc_number_range) | 改 | 字轨号段；取号与开立同一事务、不回收 | 20 |
| 12 | D 票据 | [`ivc_invoice`](#ivc_invoice) | 改 | 发票；明细、金额、路由在开立时冻结。【新】列为本次新增 | 54 |
| 13 | D 票据 | [`ivc_invoice_line`](#ivc_invoice_line) | 沿用 | 发票明细 | 11 |
| 14 | D 票据 | [`ivc_allowance`](#ivc_allowance) | 改 | 折让；账户取自原票 | 19 |
| 15 | E 买受人 | [`ivc_buyer_preference`](#ivc_buyer_preference) | 沿用 | 付款前预存的买受人选择；以最后一次为准 | 13 |
| 16 | E 买受人 | [`ivc_user_carrier`](#ivc_user_carrier) | 改 | 买家常用载具（平台级，跨主体） | 11 |
| 17 | F 往来记录 | [`ivc_transmission`](#ivc_transmission) | 改 | 异步上报任务；重试只在这里 | 18 |
| 18 | F 往来记录 | [`ivc_api_request_log`](#ivc_api_request_log) | 新 | 调用方进来的请求留痕（对照 pay_api_request_log） | 12 |
| 19 | F 往来记录 | [`ivc_provider_call_log`](#ivc_provider_call_log) | 新 | 对供应商的每一次调用 | 17 |
| 20 | F 往来记录 | [`ivc_provider_receipt`](#ivc_provider_receipt) | 新 | 外部结果进来的唯一记录（回调 / Turnkey 回执文件 / 平台结果），可回放 | 13 |
| 21 | F 往来记录 | [`ivc_raw_payload`](#ivc_raw_payload) | 新 | 原文单独存（信封加密），按合规期限清理 | 5 |
| 22 | F 往来记录 | [`ivc_biz_notify`](#ivc_biz_notify) | 新 | 独立形态下发给主应用的事件（开立、作废、打印事实）；内嵌形态走平台 Outbox | 10 |
| 23 | G 对账 | [`ivc_recon_diff`](#ivc_recon_diff) | 新 | 本地与供应商 / 税务平台状态的差异 | 12 |

## 三、逐表详情

### A 主数据

#### ivc_region

新 · 地区与税制。一行对应一个 InvoiceRegime 实现；有行无实现启动失败

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `region_code` | `VARCHAR(8) NOT NULL` | 地区码：TW / AE |
| `country_code` | `CHAR(2) NOT NULL` | ISO 3166 国家码 |
| `regime_code` | `VARCHAR(16) NOT NULL` | 税制码：TW_EGUI / AE_EINVOICE |
| `currency` | `CHAR(3) NOT NULL` | 币种 |
| `amount_scale` | `TINYINT NOT NULL` | 金额小数位（TWD=0） |
| `timezone` | `VARCHAR(32) NOT NULL` | 期别与票面时间所用时区，如 Asia/Taipei |
| `period_rule` | `VARCHAR(16) NOT NULL` | BIMONTHLY / MONTHLY / NONE |
| `rule_version` | `VARCHAR(16) NOT NULL` | 税制规则版本，如 MIG4.0 |
| `status` | `VARCHAR(16) NOT NULL` | ACTIVE / DISABLED |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_region (region_code)`

#### ivc_provider

新 · 开票供应商（加值中心 / 直连 / 测试）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `provider_code` | `VARCHAR(16) NOT NULL` | CETUSTEK / ECPAY / TURNKEY / STUB |
| `name` | `VARCHAR(64) NOT NULL` | 名称 |
| `provider_type` | `VARCHAR(24) NOT NULL` | VALUE_ADDED_CENTER / GOV_DIRECT / ASP / TEST |
| `protocol` | `VARCHAR(16) NOT NULL` | SOAP / JSON_AES / FILE |
| `sandbox_endpoint` | `VARCHAR(255) NULL` | 测试地址（Turnkey 为目录根）；不含凭据 |
| `production_endpoint` | `VARCHAR(255) NULL` | 正式地址 |
| `status` | `VARCHAR(16) NOT NULL` | ACTIVE / DISABLED（运营开关） |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |
| `updated_by` | `VARCHAR(64) NULL` | 更新人 |

键与约束：

- `UNIQUE KEY uk_ivc_provider (provider_code)`

#### ivc_provider_region

新 · 供应商 × 地区开通关系。无行 = 该供应商在该地区不可用

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `provider_id` | `BIGINT NOT NULL` | → ivc_provider.id |
| `region_id` | `BIGINT NOT NULL` | → ivc_region.id |
| `status` | `VARCHAR(16) NOT NULL` | ACTIVE / DISABLED |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |
| `updated_by` | `VARCHAR(64) NULL` | 更新人 |

键与约束：

- `UNIQUE KEY uk_ivc_provider_region (provider_id, region_id)`

#### ivc_provider_product

新 · 供应商能力（供应商 × 地区 × 票种）。启动时与代码实现的能力接口比对，不一致拒绝启用

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `provider_id` | `BIGINT NOT NULL` | → ivc_provider.id |
| `region_id` | `BIGINT NOT NULL` | → ivc_region.id |
| `doc_type` | `VARCHAR(8) NOT NULL` | B2C / B2B |
| `numbering_mode` | `VARCHAR(24) NOT NULL` | LOCAL_RANGE / PROVIDER_RANGE / PROVIDER_ONLINE / LOCAL_SEQUENCE |
| `report_mode` | `VARCHAR(16) NOT NULL` | ASYNC_UPLOAD / SYNC_ON_ISSUE / FILE_DROP |
| `supports_void` | `TINYINT NOT NULL DEFAULT 0` | 支持作废 |
| `supports_allowance` | `TINYINT NOT NULL DEFAULT 0` | 支持折让 |
| `supports_allowance_void` | `TINYINT NOT NULL DEFAULT 0` | 支持折让作废 |
| `supports_query` | `TINYINT NOT NULL DEFAULT 0` | 支持查单 |
| `supports_verify` | `TINYINT NOT NULL DEFAULT 0` | 支持账户核验（无则录入即启用） |
| `carrier_types` | `VARCHAR(128) NULL` | 支持的载具类别，逗号分隔 |
| `print_mode` | `VARCHAR(16) NOT NULL` | LOCAL（打印中心）/ PROVIDER_URL（供应商列印网址） |
| `enabled` | `TINYINT NOT NULL DEFAULT 1` | 运营开关 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |
| `updated_by` | `VARCHAR(64) NULL` | 更新人 |

键与约束：

- `UNIQUE KEY uk_ivc_provider_product (provider_id, region_id, doc_type)`

### B 营业人与账户

#### ivc_provider_credential_field

新 · 供应商凭据字段定义：前端据此渲染录入表单，后端据此校验

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `provider_id` | `BIGINT NOT NULL` | → ivc_provider.id |
| `field_code` | `VARCHAR(32) NOT NULL` | office_id / pos_no / pos_id；merchant_id / hash_key / hash_iv；sender_id |
| `label_key` | `VARCHAR(64) NOT NULL` | 多语文案键 |
| `is_secret` | `TINYINT NOT NULL` | 1=进 secret 模块、只回掩码；0=明文 |
| `is_account_code` | `TINYINT NOT NULL DEFAULT 0` | 1=该字段即 provider_account_code（每家恰好一个） |
| `required` | `TINYINT NOT NULL DEFAULT 1` | 必填 |
| `pattern` | `VARCHAR(128) NULL` | 格式正则 |
| `max_length` | `INT NOT NULL` | 最大长度 |
| `sort_no` | `INT NOT NULL` | 表单顺序 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_credential_field (provider_id, field_code)`

#### ivc_seller

新 · 营业人：主体在某地区开通发票（取代 ivc_regime_profile 的营业人部分）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `seller_no` | `VARCHAR(32) NOT NULL` | 营业人业务号 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `entity_no` | `VARCHAR(64) NOT NULL` | 主体业务号（核心系统） |
| `region_id` | `BIGINT NOT NULL` | → ivc_region.id |
| `tax_id` | `VARCHAR(32) NOT NULL` | 统编 / TRN，由税制校验 |
| `legal_name` | `VARCHAR(128) NOT NULL` | 营业人名称（快照） |
| `address` | `VARCHAR(256) NULL` | 营业地址（快照） |
| `auto_issue` | `TINYINT NOT NULL DEFAULT 0` | 付款成功后自动开立 |
| `member_carrier_type` | `VARCHAR(16) NULL` | 会员载具类别号（台湾） |
| `qr_key_id` | `BIGINT NULL` | QR 加密金钥 secret 引用（财政部按统编核发） |
| `qr_key_source` | `VARCHAR(16) NULL` | MERCHANT / PLATFORM / PROVIDER（鲸跃核验回传） |
| `qr_key_fingerprint` | `CHAR(16) NULL` | 金钥指纹，供界面显示 |
| `status` | `VARCHAR(16) NOT NULL` | ACTIVE / SUSPENDED |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `updated_by` | `VARCHAR(64) NULL` | 更新人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_seller_no (seller_no)`
- `UNIQUE KEY uk_ivc_seller (tenant_no, entity_no, region_id)`

#### ivc_provider_account

新 · 营业人在某供应商的账户（对照 pay_merchant）。凭据不在本表

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `account_no` | `VARCHAR(32) NOT NULL` | 账户业务号 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `seller_id` | `BIGINT NOT NULL` | → ivc_seller.id |
| `provider_id` | `BIGINT NOT NULL` | → ivc_provider.id |
| `environment` | `VARCHAR(16) NOT NULL` | SANDBOX / PRODUCTION |
| `provider_account_code` | `VARCHAR(64) NOT NULL` | 鲸跃 officeid / 绿界 MerchantID / Turnkey 传送者代号 |
| `managed_by` | `VARCHAR(16) NOT NULL` | MERCHANT（商家自填）/ PLATFORM（平台代维护） |
| `credential_version` | `INT NULL` | 当前生效凭据版本（→ ivc_account_credential.version） |
| `status` | `VARCHAR(16) NOT NULL` | ONBOARDING / ACTIVE / ISSUE_STOPPED / SUSPENDED / CLOSED |
| `verified_at` | `DATETIME(3) NULL` | 最近一次核验成功时间 |
| `verified_snapshot` | `LONGTEXT NULL` | 核验时供应商侧营业人资料（JSON） |
| `last_verify_error` | `VARCHAR(256) NULL` | 最近一次核验失败原因（供应商原文代码） |
| `created_via` | `VARCHAR(8) NOT NULL` | BIZ / OPS |
| `active_key` | `VARCHAR(80) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN CONCAT(seller_id,':',provider_id,':',environment) END) VIRTUAL` | 生成列：同营业人同供应商同环境至多一个 ACTIVE |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `updated_by` | `VARCHAR(64) NULL` | 更新人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_account_no (account_no)`
- `UNIQUE KEY uk_ivc_account_active (active_key)`
- `KEY idx_ivc_account_seller (seller_id, status)`
- `CONSTRAINT ck_ivc_account_snapshot CHECK (verified_snapshot IS NULL OR JSON_VALID(verified_snapshot))`

#### ivc_account_credential

新 · 凭据版本。秘密字段整组封存在 secret 模块，本表只存引用、指纹、掩码

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `account_id` | `BIGINT NOT NULL` | → ivc_provider_account.id |
| `version` | `INT NOT NULL` | 账户内递增 |
| `sec_key_id` | `BIGINT NOT NULL` | secret 模块引用（PLATFORM 命名空间，ownerType INVOICE_PROVIDER） |
| `fingerprint` | `CHAR(16) NOT NULL` | 秘密字段规范化后 SHA-256 前 16 位 |
| `masks` | `LONGTEXT NOT NULL` | 各秘密字段掩码（JSON），仅供展示 |
| `status` | `VARCHAR(16) NOT NULL` | PENDING / CURRENT / RETIRED / FAILED |
| `verify_error` | `VARCHAR(256) NULL` | 核验失败原因 |
| `created_via` | `VARCHAR(8) NOT NULL` | BIZ / OPS |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `effective_at` | `DATETIME(3) NULL` | 成为 CURRENT 的时间 |
| `retired_at` | `DATETIME(3) NULL` | 退役时间 |
| `current_key` | `BIGINT GENERATED ALWAYS AS (CASE WHEN status='CURRENT' THEN account_id END) VIRTUAL` | 生成列：每个账户恰好一个 CURRENT |

键与约束：

- `UNIQUE KEY uk_ivc_credential_version (account_id, version)`
- `UNIQUE KEY uk_ivc_credential_current (current_key)`
- `CONSTRAINT ck_ivc_credential_masks CHECK (JSON_VALID(masks))`

#### ivc_account_binding

新 · 门店 → 账户绑定；store_no='*' 为营业人级默认。只影响之后的新票

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `seller_id` | `BIGINT NOT NULL` | → ivc_seller.id |
| `store_no` | `VARCHAR(64) NOT NULL` | 门店业务号；'*' = 营业人级默认 |
| `account_id` | `BIGINT NOT NULL` | → ivc_provider_account.id |
| `effective_from` | `DATETIME(3) NOT NULL` | 生效时间 |
| `status` | `VARCHAR(16) NOT NULL` | ACTIVE / RETIRED |
| `active_key` | `VARCHAR(80) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN CONCAT(seller_id,':',store_no) END) VIRTUAL` | 生成列：一个门店同一时刻只绑一个账户 |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_binding_active (active_key)`
- `KEY idx_ivc_binding_account (account_id)`

#### ivc_account_event

新 · 账户事件，只增不改（不设审核，靠它追溯）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `account_id` | `BIGINT NOT NULL` | → ivc_provider_account.id |
| `event_type` | `VARCHAR(32) NOT NULL` | CREATED / CREDENTIAL_SUBMITTED / CREDENTIAL_EFFECTIVE / CREDENTIAL_FAILED / VERIFIED / VERIFY_FAILED / STATUS_CHANGED / MANAGEMENT_CHANGED / BINDING_CHANGED / RANGE_FETCHED |
| `operator_type` | `VARCHAR(8) NOT NULL` | BIZ / OPS / SYSTEM |
| `operator_no` | `VARCHAR(64) NULL` | 操作人 |
| `detail` | `LONGTEXT NULL` | 掩码后的前后差异（JSON），不含凭据 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |

键与约束：

- `KEY idx_ivc_account_event (account_id, created_at)`
- `CONSTRAINT ck_ivc_account_event_detail CHECK (detail IS NULL OR JSON_VALID(detail))`

### C 号码

#### ivc_number_range

改 · 字轨号段；取号与开立同一事务、不回收

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `range_no` | `VARCHAR(32) NOT NULL` | 号段业务号 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `entity_no` | `VARCHAR(64) NOT NULL` | 主体 |
| `regime_code` | `VARCHAR(16) NOT NULL` | 税制 |
| `account_id` | `BIGINT NOT NULL` | 【新】所属账户 → ivc_provider_account.id |
| `store_no` | `VARCHAR(64) NOT NULL` | '*' = 各店共用 |
| `period_code` | `VARCHAR(8) NOT NULL` | 期别，如 11512 |
| `track` | `VARCHAR(4) NOT NULL` | 字轨 |
| `start_no` | `INT NOT NULL` | 起号 |
| `end_no` | `INT NOT NULL` | 迄号 |
| `next_no` | `INT NOT NULL` | 下一个可用号；> end_no 即用尽 |
| `low_water` | `INT NOT NULL DEFAULT 0` | 【新】剩余低于此值提醒 / 自动领号 |
| `allocation_owner` | `VARCHAR(16) NOT NULL DEFAULT 'MANUAL'` | MANUAL 商家登记 / PROVIDER 供应商配给（V5） |
| `apply_id` | `VARCHAR(64) NULL` | 供应商配号申请号（V5） |
| `invoice_type` | `VARCHAR(4) NULL` | 供应商回传发票种类（V5） |
| `status` | `VARCHAR(16) NOT NULL` | ACTIVE / EXHAUSTED |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_number_range_no (tenant_no, range_no)`
- `UNIQUE KEY uk_ivc_number_range (tenant_no, entity_no, regime_code, period_code, track, start_no)`
- `KEY idx_ivc_number_range_pick (account_id, period_code, status, store_no)`
- `CONSTRAINT ck_ivc_number_range_bounds CHECK (start_no >= 0 AND start_no <= end_no AND end_no <= 99999999 AND next_no >= start_no AND next_no <= end_no + 1)`

### D 票据

#### ivc_invoice

改 · 发票；明细、金额、路由在开立时冻结。【新】列为本次新增

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `invoice_no` | `VARCHAR(32) NOT NULL` | 内部单号 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `entity_no` | `VARCHAR(64) NOT NULL` | 主体 |
| `store_no` | `VARCHAR(64) NOT NULL` | 门店 |
| `seller_id` | `BIGINT NOT NULL` | 【新】→ ivc_seller.id |
| `account_id` | `BIGINT NOT NULL` | 【新】冻结的开票账户 → ivc_provider_account.id |
| `region_code` | `VARCHAR(8) NOT NULL` | 【新】地区（快照） |
| `regime_code` | `VARCHAR(16) NOT NULL` | 税制 |
| `provider_code` | `VARCHAR(16) NOT NULL` | 【新】供应商（快照） |
| `numbering_mode` | `VARCHAR(24) NOT NULL` | 【新】编号方式（快照） |
| `industry_code` | `VARCHAR(32) NOT NULL` | 行业 |
| `source_type` | `VARCHAR(32) NOT NULL` | 来源类型 |
| `source_no` | `VARCHAR(64) NOT NULL` | 来源单号 |
| `active_source_key` | `VARCHAR(128) NULL` | 同来源同时只有一张有效票；作废 / 开立失败置空 |
| `fiscal_number` | `VARCHAR(32) NULL` | 法定号码；在线配号回写前为空【改为可空】 |
| `range_no` | `VARCHAR(32) NULL` | 号段；在线配号为空【改为可空】 |
| `period_code` | `VARCHAR(8) NULL` | 期别 |
| `random_code` | `VARCHAR(8) NULL` | 随机码 |
| `provider_order_no` | `VARCHAR(64) NULL` | 【新】供应商侧幂等身份（绿界 RelateNumber；鲸跃 OrderId） |
| `provider_ref` | `VARCHAR(128) NULL` | 【新】供应商回执号 |
| `issue_status` | `VARCHAR(16) NOT NULL` | 【新】PENDING / UNKNOWN / ISSUED / REJECTED |
| `report_status` | `VARCHAR(16) NOT NULL` | 【新】PENDING / REPORTED / REJECTED / NOT_REQUIRED |
| `seller_tax_id` | `VARCHAR(32) NOT NULL` | 卖方统编（冻结） |
| `seller_name` | `VARCHAR(128) NOT NULL` | 卖方名称（冻结） |
| `issued_at` | `DATETIME(3) NULL` | 开立时间（UTC） |
| `buyer_type` | `VARCHAR(8) NOT NULL` | B2C / B2B |
| `buyer_user_no` | `VARCHAR(64) NULL` | 平台买家用户号 |
| `buyer_tax_id` | `VARCHAR(32) NULL` | 买方统编 |
| `buyer_name` | `VARCHAR(128) NULL` | 买方名称 |
| `carrier_type` | `VARCHAR(16) NULL` | 载具类别 |
| `carrier_id` | `VARCHAR(64) NULL` | 载具号码 |
| `donate_code` | `VARCHAR(16) NULL` | 爱心码 |
| `print_mark` | `CHAR(1) NOT NULL` | Y=印证明联 |
| `currency` | `CHAR(3) NOT NULL` | 币种 |
| `sales_minor` | `BIGINT NOT NULL` | 应税销售额 |
| `zero_tax_sales_minor` | `BIGINT NOT NULL DEFAULT 0` | 零税率销售额 |
| `free_tax_sales_minor` | `BIGINT NOT NULL DEFAULT 0` | 免税销售额 |
| `tax_minor` | `BIGINT NOT NULL` | 税额 |
| `total_minor` | `BIGINT NOT NULL` | 总计 |
| `allowed_minor` | `BIGINT NOT NULL DEFAULT 0` | 累计折让 |
| `tax_type` | `VARCHAR(16) NOT NULL` | TAXABLE / ZERO / EXEMPT / MIXED |
| `tax_rate_bp` | `INT NOT NULL` | 税率（基点） |
| `status` | `VARCHAR(16) NOT NULL` | ISSUED / VOIDED（法律状态） |
| `void_reason` | `VARCHAR(256) NULL` | 作废原因 |
| `voided_at` | `DATETIME(3) NULL` | 作废时间 |
| `voided_by` | `VARCHAR(64) NULL` | 作废人 |
| `proof_snapshot` | `LONGTEXT NULL` | 证明联冻结内容；在线配号回写前为空 |
| `proof_snapshot_hash` | `CHAR(64) NULL` | 哈希 |
| `detail_snapshot` | `LONGTEXT NULL` | 交易明细冻结内容 |
| `detail_snapshot_hash` | `CHAR(64) NULL` | 哈希 |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_invoice_no (tenant_no, invoice_no)`
- `UNIQUE KEY uk_ivc_invoice_fiscal (tenant_no, region_code, period_code, fiscal_number)`
- `UNIQUE KEY uk_ivc_invoice_active_source (tenant_no, entity_no, active_source_key)`
- `UNIQUE KEY uk_ivc_invoice_provider_order (account_id, provider_order_no)`
- `KEY idx_ivc_invoice_store (tenant_no, entity_no, store_no, period_code, created_at)`
- `KEY idx_ivc_invoice_buyer (tenant_no, buyer_user_no, issued_at)`
- `KEY idx_ivc_invoice_recovery (issue_status, updated_at)`
- `CONSTRAINT ck_ivc_invoice_proof_json CHECK (proof_snapshot IS NULL OR JSON_VALID(proof_snapshot))`
- `CONSTRAINT ck_ivc_invoice_detail_json CHECK (detail_snapshot IS NULL OR JSON_VALID(detail_snapshot))`
- `CONSTRAINT ck_ivc_invoice_issued CHECK (issue_status <> 'ISSUED' OR (fiscal_number IS NOT NULL AND proof_snapshot IS NOT NULL))`
- `CONSTRAINT ck_ivc_invoice_amounts CHECK (total_minor >= 0 AND allowed_minor >= 0 AND allowed_minor <= total_minor AND sales_minor + zero_tax_sales_minor + free_tax_sales_minor + tax_minor = total_minor)`

#### ivc_invoice_line

沿用 · 发票明细

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `invoice_no` | `VARCHAR(32) NOT NULL` | 发票 |
| `line_no` | `INT NOT NULL` | 行号 |
| `source_line_ref` | `VARCHAR(64) NOT NULL` | 来源行 |
| `name` | `VARCHAR(256) NOT NULL` | 品名 |
| `quantity` | `DECIMAL(18,4) NOT NULL` | 数量 |
| `unit` | `VARCHAR(16) NULL` | 单位 |
| `unit_price_minor` | `BIGINT NOT NULL` | 单价 |
| `amount_minor` | `BIGINT NOT NULL` | 金额 |
| `tax_type` | `VARCHAR(16) NOT NULL` | 课税别 |

键与约束：

- `UNIQUE KEY uk_ivc_invoice_line (tenant_no, invoice_no, line_no)`

#### ivc_allowance

改 · 折让；账户取自原票

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `allowance_no` | `VARCHAR(32) NOT NULL` | 内部单号 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `entity_no` | `VARCHAR(64) NOT NULL` | 主体 |
| `store_no` | `VARCHAR(64) NOT NULL` | 门店 |
| `invoice_no` | `VARCHAR(32) NOT NULL` | 原发票 |
| `account_id` | `BIGINT NOT NULL` | 【新】原票账户（冗余便于任务） |
| `fiscal_allowance_number` | `VARCHAR(16) NULL` | 对外折让单号 = 号码 + 两位序号（V5） |
| `provider_allowance_no` | `VARCHAR(64) NULL` | 【新】供应商折让号（绿界） |
| `amount_minor` | `BIGINT NOT NULL` | 含税折让金额 |
| `tax_minor` | `BIGINT NOT NULL` | 税额 |
| `reason` | `VARCHAR(256) NOT NULL` | 原因 |
| `issue_status` | `VARCHAR(16) NOT NULL` | 【新】PENDING / UNKNOWN / ISSUED / REJECTED |
| `status` | `VARCHAR(16) NOT NULL` | 【新】ACTIVE / VOIDED |
| `voided_at` | `DATETIME(3) NULL` | 【新】作废时间 |
| `print_snapshot` | `LONGTEXT NULL` | 折让证明单冻结内容 |
| `print_snapshot_hash` | `CHAR(64) NULL` | 哈希 |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_allowance_no (tenant_no, allowance_no)`
- `KEY idx_ivc_allowance_invoice (tenant_no, invoice_no)`
- `CONSTRAINT ck_ivc_allowance_amount CHECK (amount_minor > 0 AND tax_minor >= 0 AND tax_minor <= amount_minor)`
- `CONSTRAINT ck_ivc_allowance_print_json CHECK (print_snapshot IS NULL OR JSON_VALID(print_snapshot))`

### E 买受人

#### ivc_buyer_preference

沿用 · 付款前预存的买受人选择；以最后一次为准

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `entity_no` | `VARCHAR(64) NOT NULL` | 主体 |
| `source_type` | `VARCHAR(32) NOT NULL` | 来源类型 |
| `source_no` | `VARCHAR(64) NOT NULL` | 来源单号 |
| `buyer_tax_id` | `VARCHAR(32) NULL` | 买方统编 |
| `buyer_name` | `VARCHAR(128) NULL` | 买方名称 |
| `carrier_type` | `VARCHAR(16) NULL` | 载具类别 |
| `carrier_id` | `VARCHAR(64) NULL` | 载具号码 |
| `donate_code` | `VARCHAR(16) NULL` | 爱心码 |
| `created_by` | `VARCHAR(64) NULL` | 创建人 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_buyer_preference (tenant_no, entity_no, source_type, source_no)`

#### ivc_user_carrier

改 · 买家常用载具（平台级，跨主体）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `carrier_no` | `VARCHAR(32) NOT NULL` | 业务号 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `user_no` | `VARCHAR(64) NOT NULL` | 买家 |
| `region_code` | `VARCHAR(8) NOT NULL DEFAULT 'TW'` | 【新】载具所属地区 |
| `carrier_type` | `VARCHAR(16) NOT NULL` | 3J0002 手机条码 / CQ0001 自然人凭证 |
| `carrier_id` | `VARCHAR(64) NOT NULL` | 载具号码 |
| `label` | `VARCHAR(32) NULL` | 买家自起名 |
| `is_default` | `TINYINT NOT NULL DEFAULT 0` | 默认 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_user_carrier_no (tenant_no, carrier_no)`
- `UNIQUE KEY uk_ivc_user_carrier (tenant_no, user_no, region_code, carrier_type, carrier_id)`

### F 往来记录

#### ivc_transmission

改 · 异步上报任务；重试只在这里

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `transmission_no` | `VARCHAR(32) NOT NULL` | 业务号 |
| `tenant_no` | `VARCHAR(32) NOT NULL` | 租户 |
| `entity_no` | `VARCHAR(64) NOT NULL` | 主体 |
| `account_id` | `BIGINT NOT NULL` | 【新】原票账户 |
| `regime_code` | `VARCHAR(16) NOT NULL` | 税制 |
| `provider_code` | `VARCHAR(16) NOT NULL` | 供应商（原 gateway_code） |
| `operation` | `VARCHAR(16) NOT NULL` | ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID（原 message_type） |
| `ref_no` | `VARCHAR(32) NOT NULL` | invoice_no / allowance_no |
| `payload` | `LONGTEXT NOT NULL` | 冻结报文，补传原样再发 |
| `status` | `VARCHAR(16) NOT NULL` | PENDING / SENDING / SENT / FAILED |
| `attempts` | `INT NOT NULL DEFAULT 0` | 已尝试次数 |
| `next_attempt_at` | `DATETIME(3) NOT NULL` | 下次尝试 |
| `last_error` | `VARCHAR(512) NULL` | 最近错误 |
| `provider_ref` | `VARCHAR(128) NULL` | 供应商回执号 |
| `deadline_at` | `DATETIME(3) NULL` | 【新】法定上报期限（台湾 B2C 开立后 48 小时） |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_transmission_no (tenant_no, transmission_no)`
- `UNIQUE KEY uk_ivc_transmission_ref (tenant_no, operation, ref_no)`
- `KEY idx_ivc_transmission_due (status, next_attempt_at)`
- `CONSTRAINT ck_ivc_transmission_payload CHECK (JSON_VALID(payload))`

#### ivc_api_request_log

新 · 调用方进来的请求留痕（对照 pay_api_request_log）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `request_no` | `VARCHAR(32) NOT NULL` | 业务号 |
| `trace_no` | `VARCHAR(64) NULL` | 追踪号 |
| `caller_type` | `VARCHAR(8) NOT NULL` | BIZ / MP / OPS / OPEN / INTERNAL |
| `caller_ref` | `VARCHAR(64) NULL` | 主体 / 应用 / 操作人 |
| `method` | `VARCHAR(8) NOT NULL` | HTTP 方法 |
| `path` | `VARCHAR(255) NOT NULL` | 路径模板 |
| `idempotency_key` | `VARCHAR(64) NULL` | 幂等键 |
| `request_masked` | `LONGTEXT NULL` | 脱敏请求 |
| `response_code` | `INT NULL` | 业务码 |
| `elapsed_ms` | `INT NULL` | 耗时 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_api_request_no (request_no)`
- `KEY idx_ivc_api_request_idem (caller_type, caller_ref, idempotency_key)`

#### ivc_provider_call_log

新 · 对供应商的每一次调用

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `call_no` | `VARCHAR(32) NOT NULL` | 业务号 |
| `trace_no` | `VARCHAR(64) NULL` | 追踪号 |
| `account_id` | `BIGINT NOT NULL` | 账户 |
| `provider_code` | `VARCHAR(16) NOT NULL` | 供应商 |
| `operation` | `VARCHAR(24) NOT NULL` | ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID / QUERY / RANGE / VERIFY |
| `ref_type` | `VARCHAR(16) NULL` | INVOICE / ALLOWANCE / ACCOUNT / RANGE |
| `ref_no` | `VARCHAR(64) NULL` | 业务号 |
| `attempt` | `INT NOT NULL DEFAULT 1` | 第几次 |
| `request_masked` | `LONGTEXT NULL` | 脱敏请求 |
| `response_masked` | `LONGTEXT NULL` | 脱敏响应 |
| `raw_payload_id` | `BIGINT NULL` | → ivc_raw_payload.id（加密原文） |
| `http_status` | `INT NULL` | HTTP 状态 |
| `elapsed_ms` | `INT NULL` | 耗时 |
| `outcome` | `VARCHAR(16) NOT NULL` | ACCEPTED / REJECTED / UNKNOWN |
| `provider_result_code` | `VARCHAR(64) NULL` | 供应商原文结果码（S0 / RtnCode …） |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_call_no (call_no)`
- `KEY idx_ivc_call_ref (ref_type, ref_no)`
- `KEY idx_ivc_call_account (account_id, created_at)`

#### ivc_provider_receipt

新 · 外部结果进来的唯一记录（回调 / Turnkey 回执文件 / 平台结果），可回放

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `receipt_no` | `VARCHAR(32) NOT NULL` | 业务号 |
| `account_id` | `BIGINT NULL` | 账户（验签前可空） |
| `provider_code` | `VARCHAR(16) NOT NULL` | 供应商 |
| `kind` | `VARCHAR(16) NOT NULL` | CALLBACK / FILE_RECEIPT / PLATFORM_RESULT |
| `ref_no` | `VARCHAR(64) NULL` | 关联业务号 |
| `raw_payload_id` | `BIGINT NOT NULL` | → ivc_raw_payload.id |
| `raw_sha256` | `CHAR(64) NOT NULL` | 原文哈希 |
| `verify_result` | `VARCHAR(16) NULL` | PASSED / FAILED / SKIPPED |
| `outcome` | `VARCHAR(16) NULL` | APPLIED / IGNORED / FAILED |
| `replay_of` | `BIGINT NULL` | 回放来源 → ivc_provider_receipt.id |
| `received_at` | `DATETIME(3) NOT NULL` | 收到时间 |
| `handled_at` | `DATETIME(3) NULL` | 处理完成时间 |

键与约束：

- `UNIQUE KEY uk_ivc_receipt_no (receipt_no)`
- `KEY idx_ivc_receipt_ref (provider_code, ref_no)`

#### ivc_raw_payload

新 · 原文单独存（信封加密），按合规期限清理

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `cipher` | `LONGBLOB NOT NULL` | 加密原文 |
| `sha256` | `CHAR(64) NOT NULL` | 原文哈希 |
| `expire_at` | `DATETIME(3) NOT NULL` | 到期清理时间 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |

键与约束：

- `KEY idx_ivc_raw_expire (expire_at)`

#### ivc_biz_notify

新 · 独立形态下发给主应用的事件（开立、作废、打印事实）；内嵌形态走平台 Outbox

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `notify_no` | `VARCHAR(32) NOT NULL` | 业务号 |
| `event_type` | `VARCHAR(48) NOT NULL` | InvoiceIssued / InvoiceVoided / BusinessPrintFactReady … |
| `payload` | `LONGTEXT NOT NULL` | 事件内容（JSON） |
| `status` | `VARCHAR(16) NOT NULL` | PENDING / SENT / FAILED |
| `attempts` | `INT NOT NULL DEFAULT 0` | 已尝试 |
| `next_attempt_at` | `DATETIME(3) NOT NULL` | 下次尝试 |
| `last_error` | `VARCHAR(512) NULL` | 最近错误 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |
| `updated_at` | `DATETIME(3) NOT NULL` | 更新时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_notify_no (notify_no)`
- `KEY idx_ivc_notify_due (status, next_attempt_at)`
- `CONSTRAINT ck_ivc_notify_payload CHECK (JSON_VALID(payload))`

### G 对账

#### ivc_recon_diff

新 · 本地与供应商 / 税务平台状态的差异

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 主键 |
| `recon_date` | `DATE NOT NULL` | 对账日 |
| `account_id` | `BIGINT NOT NULL` | 账户 |
| `ref_type` | `VARCHAR(16) NOT NULL` | INVOICE / ALLOWANCE |
| `ref_no` | `VARCHAR(64) NOT NULL` | 业务号 |
| `local_state` | `VARCHAR(32) NULL` | 本地状态 |
| `provider_state` | `VARCHAR(32) NULL` | 供应商状态 |
| `diff_type` | `VARCHAR(24) NOT NULL` | MISSING_REMOTE / MISSING_LOCAL / STATE_MISMATCH / AMOUNT_MISMATCH |
| `resolution` | `VARCHAR(16) NULL` | OPEN / FIXED / ACCEPTED |
| `resolved_by` | `VARCHAR(64) NULL` | 处理人 |
| `resolved_at` | `DATETIME(3) NULL` | 处理时间 |
| `created_at` | `DATETIME(3) NOT NULL` | 创建时间（UTC） |

键与约束：

- `UNIQUE KEY uk_ivc_recon_diff (recon_date, account_id, ref_type, ref_no)`
