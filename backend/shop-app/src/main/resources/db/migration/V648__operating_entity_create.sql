-- shop-merchant: independent account-owned operating entity; no legal/payment activation.
CREATE TABLE mch_operating_entity (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '认证租户',
  operating_entity_no VARCHAR(64) NOT NULL COMMENT '独立经营主体号',
  owner_user_no VARCHAR(64) NOT NULL COMMENT '真实USR所有人，不是员工号',
  name VARCHAR(100) NOT NULL COMMENT '经营名称',
  market VARCHAR(8) NOT NULL COMMENT '创建时继承账号市场，不可变',
  legal_subject_no VARCHAR(64) NULL COMMENT '可选本人已审核证照，非占位对象',
  status VARCHAR(16) NOT NULL COMMENT 'ENABLED或DISABLED，不代表法定审核或收款资格',
  create_key VARCHAR(64) NOT NULL COMMENT '账户范围永久创建键',
  create_hash CHAR(64) NOT NULL COMMENT '规范化创建内容摘要',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '版本',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '保留创建回执，不开放删除',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC创建时间',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC修改时间',
  created_by VARCHAR(64) NOT NULL COMMENT '认证创建身份',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改身份',
  UNIQUE KEY uk_operating_entity_no (tenant_no,operating_entity_no),
  UNIQUE KEY uk_operating_entity_create (tenant_no,owner_user_no,create_key),
  CONSTRAINT ck_operating_entity_market CHECK (market IN ('CN','TW','AE')),
  CONSTRAINT ck_operating_entity_status CHECK (status IN ('ENABLED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;
