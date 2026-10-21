-- DESIGN ONLY. NOT A FLYWAY MIGRATION. DO NOT EXECUTE ON A LIVE DATABASE.
-- MySQL 8.0+; final collation and CHECK enforcement must match target environment.
-- Phase P1 only. No Pay schema or account balance mutation.

-- shop-merchant · 经营管理授权空间，不是资金账户或品牌与主体归属关系
CREATE TABLE mch_management_space (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键，不对外',
  tenant_no VARCHAR(32) NOT NULL COMMENT '服务端可信租户',
  management_no VARCHAR(64) NOT NULL COMMENT '管理空间号',
  name VARCHAR(64) NOT NULL COMMENT '空间名称',
  owner_actor_no VARCHAR(64) NOT NULL COMMENT '有效所有人，变更需审计',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE/SUSPENDED',
  bootstrap_source_account_no VARCHAR(64) NOT NULL COMMENT '创建时验证的现有所有人账号关系，不授予其余主体权限',
  bootstrap_key VARCHAR(64) NOT NULL COMMENT '创建幂等键，同一所有人相同键返回原空间',
  bootstrap_hash CHAR(64) NOT NULL COMMENT '创建请求规范化摘要',
  auth_revision BIGINT NOT NULL DEFAULT 0 COMMENT '空间授权版本，授予及撤销递增',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '本阶段不提供删除，保留历史唯一性',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证创建人',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,management_no),
  UNIQUE KEY uk_2 (tenant_no,owner_actor_no,bootstrap_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

-- shop-merchant · 独立授予品牌、门店及主体管理权限
CREATE TABLE mch_management_grant (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键，不对外',
  tenant_no VARCHAR(32) NOT NULL COMMENT '服务端可信租户',
  grant_no VARCHAR(64) NOT NULL COMMENT '授权号',
  management_no VARCHAR(64) NOT NULL COMMENT '空间号',
  actor_no VARCHAR(64) NOT NULL COMMENT '现有认证主体标识',
  resource_type VARCHAR(24) NOT NULL COMMENT 'SPACE/BRAND/STORE/ENTITY/PAYMENT_MERCHANT，各自授权',
  resource_no VARCHAR(64) NOT NULL COMMENT '资源号',
  permission_code VARCHAR(64) NOT NULL COMMENT '能力码，复用权限注册机制',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE/REVOKED',
  valid_from DATETIME(3) NOT NULL COMMENT 'UTC生效',
  valid_to DATETIME(3) NULL COMMENT 'UTC失效',
  authority_source_type VARCHAR(24) NOT NULL COMMENT 'OWNER_ACCOUNT/PARENT_GRANT/SPACE_OWNER，实时复核授权来源',
  authority_source_no VARCHAR(64) NOT NULL COMMENT '原始账号关系号、父授权号或空间号',
  delegatable TINYINT NOT NULL DEFAULT 0 COMMENT '可否继续委派本资源本能力',
  authority_snapshot JSON NOT NULL COMMENT '创建时来源摘要，仅审计不代替实时权限',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '本阶段不提供删除，保留历史唯一性',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证创建人',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,grant_no),
  UNIQUE KEY uk_2 (tenant_no,management_no,actor_no,resource_type,resource_no,permission_code),
  KEY ix_1 (tenant_no,actor_no,status),
  KEY ix_2 (tenant_no,resource_type,resource_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

-- shop-merchant · 经营品牌，不直接归属经营主体
CREATE TABLE mch_brand (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键，不对外',
  tenant_no VARCHAR(32) NOT NULL COMMENT '服务端可信租户',
  brand_no VARCHAR(64) NOT NULL COMMENT '品牌号',
  management_no VARCHAR(64) NOT NULL COMMENT '所属经营授权空间，非经营主体',
  name VARCHAR(100) NOT NULL COMMENT '品牌名',
  logo VARCHAR(512) NULL COMMENT '标识资源',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT/ACTIVE/INACTIVE',
  scope_revision BIGINT NOT NULL COMMENT '门店范围版本',
  name_key VARCHAR(100) NOT NULL COMMENT '名称trim及Unicode NFC规范化，唯一性按目标库排序规则',
  create_key VARCHAR(64) NOT NULL COMMENT '同空间创建幂等键',
  create_hash CHAR(64) NOT NULL COMMENT '创建请求规范化摘要',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '本阶段不提供删除，保留历史唯一性',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证创建人',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,brand_no),
  UNIQUE KEY uk_2 (tenant_no,management_no,name_key),
  UNIQUE KEY uk_3 (tenant_no,management_no,create_key),
  KEY ix_1 (tenant_no,management_no,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

-- shop-merchant · 门店品牌有效期关系；品牌与主体无直接关系表
CREATE TABLE mch_brand_store_binding (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键，不对外',
  tenant_no VARCHAR(32) NOT NULL COMMENT '服务端可信租户',
  binding_no VARCHAR(64) NOT NULL COMMENT '绑定号',
  brand_no VARCHAR(64) NOT NULL COMMENT '品牌',
  store_no VARCHAR(64) NOT NULL COMMENT '门店',
  status VARCHAR(24) NOT NULL COMMENT 'PREPARING/ACTIVE/ENDED',
  effective_from DATETIME(3) NOT NULL COMMENT 'UTC生效',
  effective_to DATETIME(3) NULL COMMENT 'UTC结束',
  scope_revision BIGINT NOT NULL COMMENT '品牌范围版本',
  change_no VARCHAR(64) NOT NULL COMMENT '关联变更单',
  active_store_no VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN store_no ELSE NULL END) STORED COMMENT '当前绑定唯一辅助键',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '本阶段不提供删除，保留历史唯一性',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证创建人',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,binding_no),
  UNIQUE KEY uk_2 (tenant_no,active_store_no),
  KEY ix_1 (tenant_no,brand_no,status,store_no),
  KEY ix_2 (tenant_no,store_no,effective_from),
  CHECK (status IN ('PREPARING','ACTIVE','ENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

-- shop-merchant · 加入退出品牌及独立影响检查，不迁移资金
CREATE TABLE mch_brand_change (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键，不对外',
  tenant_no VARCHAR(32) NOT NULL COMMENT '服务端可信租户',
  change_no VARCHAR(64) NOT NULL COMMENT '变更号',
  management_no VARCHAR(64) NOT NULL COMMENT '管理上下文',
  store_no VARCHAR(64) NOT NULL COMMENT '门店',
  from_brand_no VARCHAR(64) NULL COMMENT '原品牌',
  to_brand_no VARCHAR(64) NULL COMMENT '目标品牌，NULL退出',
  expected_scope_revision BIGINT NOT NULL COMMENT '提交时范围版本',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT/CHECKED/BLOCKED/PREPARING/COMPLETED/REJECTED',
  check_snapshot JSON NOT NULL COMMENT '会员、预占、原发行方、账单检查结果',
  authorization_snapshot JSON NOT NULL COMMENT '品牌与门店双方授权依据',
  reason VARCHAR(255) NULL COMMENT '原因',
  expected_store_version BIGINT NOT NULL COMMENT '提交时门店乐观锁版本，不改变门店经营主体',
  expected_from_brand_revision BIGINT NULL COMMENT '原品牌范围版本，首次加入可空',
  expected_to_brand_revision BIGINT NULL COMMENT '目标品牌范围版本，退出可空',
  command_key VARCHAR(64) NOT NULL COMMENT '同店提交幂等键',
  command_hash CHAR(64) NOT NULL COMMENT '规范化提交摘要',
  check_expires_at DATETIME(3) NOT NULL COMMENT '检查时效，到期或数据变化必须重新检查',
  submitted_by VARCHAR(64) NOT NULL COMMENT '发起认证身份',
  decision VARCHAR(16) NULL COMMENT 'APPROVE/REJECT；批准不等于已生效',
  decided_by VARCHAR(64) NULL COMMENT '决定认证身份',
  decided_at DATETIME(3) NULL COMMENT 'UTC决定时间',
  decision_key VARCHAR(64) NULL COMMENT '决定命令幂等键',
  decision_hash CHAR(64) NULL COMMENT '决定命令摘要',
  pending_store_no VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('CHECKED','PREPARING') THEN store_no ELSE NULL END) STORED COMMENT '同店最多一笔待决或执行中变更',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '本阶段不提供删除，保留历史唯一性',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证创建人',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,change_no),
  UNIQUE KEY uk_2 (tenant_no,store_no,command_key),
  UNIQUE KEY uk_3 (tenant_no,pending_store_no),
  KEY ix_1 (tenant_no,store_no,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;
