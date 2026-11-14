CREATE TABLE mbr_scope_member (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  scope_member_no VARCHAR(64) NOT NULL COMMENT '新关系号',
  scope_type VARCHAR(24) NOT NULL COMMENT 'BRAND/STORE',
  scope_no VARCHAR(64) NOT NULL COMMENT '品牌或独立门店号',
  customer_no VARCHAR(64) NOT NULL COMMENT '统一可信人档canonical identity',
  first_store_no VARCHAR(64) NOT NULL COMMENT '首次来源店',
  status VARCHAR(24) NOT NULL COMMENT 'LEAD/ACTIVE/BLOCKED',
  joined_at DATETIME(3) NOT NULL COMMENT 'UTC入会',
  shared_profile JSON NOT NULL COMMENT '仅允许共享的品牌资料',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,scope_member_no),
  UNIQUE KEY uk_2 (tenant_no,scope_type,scope_no,customer_no),
  KEY ix_1 (tenant_no,scope_type,scope_no,joined_at,scope_member_no),
  CHECK (scope_type IN ('BRAND','STORE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

-- shop-core/member · 本店私有关系和资料，跨店不默认共享
CREATE TABLE mbr_store_profile (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  store_profile_no VARCHAR(64) NOT NULL COMMENT '关系号',
  customer_no VARCHAR(64) NOT NULL COMMENT '可信人档',
  store_no VARCHAR(64) NOT NULL COMMENT '本店',
  remark VARCHAR(500) NULL COMMENT '私有备注',
  advisor_actor_no VARCHAR(64) NULL COMMENT '顾问',
  first_seen_at DATETIME(3) NOT NULL COMMENT '首次服务',
  last_seen_at DATETIME(3) NULL COMMENT '最近服务',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,store_profile_no),
  UNIQUE KEY uk_2 (tenant_no,store_no,customer_no),
  KEY ix_1 (tenant_no,store_no,last_seen_at,customer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

-- shop-core/member · 历史主体会员到运营范围的审计映射，不能按手机号直接合并
CREATE TABLE mbr_scope_legacy_link (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  link_no VARCHAR(64) NOT NULL COMMENT '映射号',
  legacy_member_no VARCHAR(64) NOT NULL COMMENT '原mbr_member号',
  scope_member_no VARCHAR(64) NOT NULL COMMENT '新关系号',
  migration_no VARCHAR(64) NOT NULL COMMENT '迁移批次',
  identity_evidence JSON NOT NULL COMMENT '脱敏核实依据',
  status VARCHAR(24) NOT NULL COMMENT 'VERIFIED/CONFLICT',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,link_no),
  UNIQUE KEY uk_2 (tenant_no,legacy_member_no,scope_member_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;


ALTER TABLE mbr_identity_ticket
 ADD COLUMN operator_scope_type VARCHAR(24) NULL,
 ADD COLUMN issuer_no VARCHAR(64) NULL,
 ADD COLUMN brand_scope_revision BIGINT NULL,
 ADD COLUMN scope_member_no VARCHAR(64) NULL;
