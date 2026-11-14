-- 支付服务 v4 · 迁移 7（I3 · S1）：接入方。内部系统也注册成 app（MALL / FOOD），支付单与流水的「来源」才拆得开。

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

-- 内部接入方：商城、餐饮。owner_type=INTERNAL 不经开放 API 签名，经 PaymentPort（内嵌）或 InternalCallerFilter（独立）进来
INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps, daily_refund_limit_minor, sandbox,
    created_at, updated_at) VALUES
('MALL', '商城', 'INTERNAL', 'ACTIVE', 'pay:create,pay:read,pay:refund', 200, 0, 0, NOW(3), NOW(3)),
('FOOD', '餐饮', 'INTERNAL', 'ACTIVE', 'pay:create,pay:read,pay:refund', 200, 0, 0, NOW(3), NOW(3));
