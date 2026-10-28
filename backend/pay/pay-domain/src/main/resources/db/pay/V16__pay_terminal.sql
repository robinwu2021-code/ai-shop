-- 支付服务 v4 · 迁移 16（pay-guard 开发计划 M6.1）：POS 终端三张表 —— 终端（pay_terminal）、店长授权码（pay_manager_code）、终端日结（pay_terminal_batch）。
-- 列逐字取自真源 docs/technical/design/schema/pay-service-database.json；设备密钥不建表：归 secret 模块（sec_key owner_type=TERMINAL）。

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
) COMMENT='刷卡机终端：刷卡机终端（自研 App）：绑定收款商户与门店';

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
) COMMENT='店长授权码：店长授权码：终端撤销 / 退款的授权，由商家端设置，只存哈希';

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
) COMMENT='终端日结：终端日结：终端上报与服务端汇总比对';
