-- 支付服务 v4 · 迁移 11（I5 · S1）：接入层的三张表 —— 门店授权接入方（pay_app_grant）、签名防重放（pay_api_nonce，无 Redis 时用）、静态码（pay_qr_code）。
-- DDL 逐字取自 docs/technical/design/schema/pay-service-review.sql（TDD-支付服务-I5接入层）。接入方密钥不建表：归平台级 secret 模块。

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

-- 收银台静态码的接入方（TDD-支付服务-I5接入层 S7）：pay_order.pay_app_id 必填、(pay_app_id, out_order_no) 唯一 ——
-- 顾客扫静态码建的单没有业务方，记在它名下；托管收银台（第三方建的单）仍归第三方，不经它。不能退款（退款走商家端）。
INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps, daily_refund_limit_minor, sandbox,
    created_at, updated_at) VALUES
('CHECKOUT', '收银台静态码', 'INTERNAL', 'ACTIVE', 'pay:create,pay:read', 200, 0, 0, NOW(3), NOW(3));
