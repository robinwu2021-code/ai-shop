-- db/secret V1 · 平台级密钥管理 sec_* 七张表（TDD-支付服务-W5密钥模块；表结构说明 §3 / §6.8）。
-- 由 shop-app SecretDataSourceConfig#secretFlyway 执行，历史记在 sec_flyway_history，号从 V1 起、不与平台和 db/pay 抢号。
-- DDL 逐字取自 docs/technical/design/schema/pay-service-review.sql「密钥管理」段；结构以表结构说明 §6.8 为准。
-- 本库不存任何主密钥材料：主密钥在 KMS / HSM / 部署环境注入，sec_master_key 只登记名字。

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
