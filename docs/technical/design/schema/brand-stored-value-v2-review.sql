-- DESIGN ONLY. DO NOT APPLY AS A FLYWAY MIGRATION.
-- 新表逻辑模块在注释中；Core/Pay使用各自数据源，无跨域物理外键。
-- 所有字段和索引均为评审建议，实际迁移须盘点生产版本和存量后编号。
-- MySQL 8.0+；不使用 CREATE IF NOT EXISTS 掩盖结构漂移。

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- shop-core/member · 品牌或独立门店会员关系，不替换平台人档
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- shop-core/promotion · Core活动版本到Pay唯一发行配置及发布状态
CREATE TABLE pmt_sv_release_link (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  link_no VARCHAR(64) NOT NULL COMMENT '关联号',
  activity_no VARCHAR(64) NOT NULL COMMENT '现有pmt_activity',
  activity_version_no VARCHAR(64) NOT NULL COMMENT '现有不可变发布版本',
  issuer_no VARCHAR(64) NOT NULL COMMENT 'Pay发行方引用',
  release_no VARCHAR(64) NOT NULL COMMENT 'Pay不可变发行配置引用',
  release_hash CHAR(64) NOT NULL COMMENT '只存内容摘要，金额真源在Pay',
  publish_command_no VARCHAR(64) NOT NULL COMMENT '稳定跨域幂等命令',
  state VARCHAR(24) NOT NULL COMMENT 'PREPARING/PAY_READY/ACTIVE/STOP_REQUESTED/STOPPED/FAILED',
  permission_snapshot JSON NOT NULL COMMENT '发布／审批依据',
  policy_versions JSON NOT NULL COMMENT 'Pay复核过的限制版本',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,link_no),
  UNIQUE KEY uk_2 (tenant_no,activity_version_no),
  UNIQUE KEY uk_3 (tenant_no,release_no),
  UNIQUE KEY uk_4 (tenant_no,publish_command_no),
  KEY ix_1 (tenant_no,state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- shop-core/promotion · 仅门店发行审批，独立于已发布版本
CREATE TABLE pmt_sv_approval (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  approval_no VARCHAR(64) NOT NULL COMMENT '审批单',
  activity_no VARCHAR(64) NOT NULL COMMENT '活动',
  draft_revision_no VARCHAR(64) NOT NULL COMMENT '精确草稿修订号',
  draft_hash CHAR(64) NOT NULL COMMENT '申请内容摘要',
  store_no VARCHAR(64) NOT NULL COMMENT '申请门店',
  management_no VARCHAR(64) NOT NULL COMMENT '有权审批空间',
  submitted_by VARCHAR(64) NOT NULL COMMENT '申请人',
  decided_by VARCHAR(64) NULL COMMENT '审批人',
  status VARCHAR(24) NOT NULL COMMENT 'PENDING/APPROVED/RETURNED/STALE',
  policy_versions JSON NOT NULL COMMENT '提交时策略版本，仅审计，批准重新检查',
  decision_reason VARCHAR(255) NULL COMMENT '审批理由',
  decided_at DATETIME(3) NULL COMMENT '决定时间',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,approval_no),
  UNIQUE KEY uk_2 (tenant_no,activity_no,draft_revision_no),
  KEY ix_1 (tenant_no,management_no,status,created_at,approval_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 品牌与单店发行方各自唯一
CREATE TABLE sv_issuer (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  issuer_no VARCHAR(64) NOT NULL COMMENT '发行方号',
  scope_type VARCHAR(24) NOT NULL COMMENT 'BRAND/STORE',
  scope_no VARCHAR(64) NOT NULL COMMENT '品牌或门店',
  name VARCHAR(64) NOT NULL COMMENT '发行方名称',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT/ACTIVE/ISSUE_STOPPED',
  current_issuer_version_no VARCHAR(64) NOT NULL COMMENT '当前结算和清分版本',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,issuer_no),
  UNIQUE KEY uk_2 (tenant_no,scope_type,scope_no),
  CHECK (scope_type IN ('BRAND','STORE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 不可变收款与结算规则；不是品牌主体组织关系
CREATE TABLE sv_issuer_version (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  issuer_version_no VARCHAR(64) NOT NULL COMMENT '配置版本',
  issuer_no VARCHAR(64) NOT NULL COMMENT '发行方',
  version_index BIGINT NOT NULL COMMENT '递增版本',
  settlement_entity_no VARCHAR(64) NOT NULL COMMENT '指定责任／结算主体',
  collection_mode VARCHAR(24) NOT NULL COMMENT 'CENTRALIZED/STORE_LOCAL',
  clearing_mode VARCHAR(24) NOT NULL COMMENT 'PRINCIPAL_COMPONENT/INTERNAL_RECON',
  debit_allocation_mode VARCHAR(24) NOT NULL COMMENT 'PRO_RATA/LEGACY_SNAPSHOT',
  bonus_bearer VARCHAR(24) NOT NULL COMMENT 'ISSUER',
  currency VARCHAR(8) NOT NULL COMMENT '一个规则版本一种币种',
  market_zone VARCHAR(64) NOT NULL COMMENT '账期时区',
  collection_config JSON NOT NULL COMMENT '授权收款配置引用，不存密钥',
  clearing_rule JSON NOT NULL COMMENT '舍入及承担规则版本',
  entity_acceptance JSON NOT NULL COMMENT '结算主体授权确认依据',
  content_hash CHAR(64) NOT NULL COMMENT 'canonical JSON摘要',
  effective_at DATETIME(3) NOT NULL COMMENT 'UTC生效',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,issuer_version_no),
  UNIQUE KEY uk_2 (tenant_no,issuer_no,version_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · Core范围事实的Pay生效镜像，非第二个品牌管理系统
CREATE TABLE sv_issuer_store_access (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  access_no VARCHAR(64) NOT NULL COMMENT '准入号',
  issuer_no VARCHAR(64) NOT NULL COMMENT '发行方',
  store_no VARCHAR(64) NOT NULL COMMENT '门店',
  operating_entity_no VARCHAR(64) NOT NULL COMMENT '当前经营主体事实',
  source_binding_no VARCHAR(64) NOT NULL COMMENT 'Core品牌绑定或单店事实',
  source_revision BIGINT NOT NULL COMMENT '源范围修订',
  can_issue TINYINT NOT NULL COMMENT '能否办理新充值',
  can_redeem TINYINT NOT NULL COMMENT '能否核销',
  status VARCHAR(24) NOT NULL COMMENT 'PREPARING/ACTIVE/SUSPENDED/ENDED',
  effective_from DATETIME(3) NOT NULL COMMENT 'UTC生效',
  effective_to DATETIME(3) NULL COMMENT 'UTC结束',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,access_no),
  UNIQUE KEY uk_2 (tenant_no,issuer_no,store_no),
  KEY ix_1 (tenant_no,store_no,status,issuer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 门店独立发行限制的作用范围
CREATE TABLE sv_issue_policy (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  policy_no VARCHAR(64) NOT NULL COMMENT '策略号',
  management_no VARCHAR(64) NOT NULL COMMENT '经营管控空间',
  target_type VARCHAR(24) NOT NULL COMMENT 'SPACE/BRAND_STORES/STORE',
  target_no VARCHAR(64) NOT NULL COMMENT '作用对象',
  currency VARCHAR(8) NOT NULL COMMENT '金额上限币种',
  current_version_no VARCHAR(64) NOT NULL COMMENT '当前不可变策略版本',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,policy_no),
  UNIQUE KEY uk_2 (tenant_no,management_no,target_type,target_no,currency),
  CHECK (target_type IN ('SPACE','BRAND_STORES','STORE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 仅允许收紧的额度与审批规则
CREATE TABLE sv_issue_policy_version (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  policy_version_no VARCHAR(64) NOT NULL COMMENT '策略版本',
  policy_no VARCHAR(64) NOT NULL COMMENT '策略',
  version_index BIGINT NOT NULL COMMENT '递增',
  allow_issue TINYINT NOT NULL COMMENT '允许单店新发行',
  require_approval TINYINT NOT NULL COMMENT '要求审批，父子OR',
  max_bonus_bps INT NOT NULL COMMENT '比例万分比，20%=2000',
  max_paid_minor BIGINT NOT NULL COMMENT '单笔实收上限',
  max_bonus_minor BIGINT NOT NULL COMMENT '单笔赠送上限',
  max_active_days INT NOT NULL COMMENT '最大活动自然日数',
  max_outstanding_minor BIGINT NOT NULL COMMENT '未消费余额上限，含赠金和已收款占位',
  effect_mode VARCHAR(24) NOT NULL COMMENT 'NEW_ISSUANCE_ONLY',
  parent_version_refs JSON NOT NULL COMMENT '继承规则版本',
  content_hash CHAR(64) NOT NULL COMMENT '摘要',
  effective_at DATETIME(3) NOT NULL COMMENT '生效时间',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,policy_version_no),
  UNIQUE KEY uk_2 (tenant_no,policy_no,version_index),
  CHECK (max_bonus_bps>=0 AND max_bonus_bps<=10000),
  CHECK (max_paid_minor>0 AND max_bonus_minor>=0 AND max_outstanding_minor>0),
  CHECK (max_active_days>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 活动版本对应的不可变金融发行配置
CREATE TABLE sv_release (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  release_no VARCHAR(64) NOT NULL COMMENT '发行配置号',
  issuer_no VARCHAR(64) NOT NULL COMMENT '发行方',
  issuer_version_no VARCHAR(64) NOT NULL COMMENT '冻结收款及清分版本',
  activity_no VARCHAR(64) NULL COMMENT 'V2的Core活动；旧PLAN历史为NULL，不伪造活动',
  activity_version_no VARCHAR(64) NULL COMMENT 'Core发布版本；草稿为NULL，发布只绑定一次',
  draft_revision_no VARCHAR(64) NULL COMMENT 'V2草稿修订；历史PLAN为NULL',
  release_index BIGINT NOT NULL COMMENT '发行版本序号',
  source_kind VARCHAR(24) NOT NULL COMMENT 'V2_ACTIVITY/LEGACY_PLAN',
  legacy_plan_version_no VARCHAR(64) NULL COMMENT '原单档方案版本',
  currency VARCHAR(8) NOT NULL COMMENT '币种',
  starts_at DATETIME(3) NOT NULL COMMENT 'UTC开始',
  ends_at DATETIME(3) NULL COMMENT 'UTC结束',
  state VARCHAR(24) NOT NULL COMMENT 'PREPARED/ACTIVE/ISSUE_STOPPED/ABORTED',
  redemption_scope_mode VARCHAR(24) NOT NULL COMMENT 'BRAND_DYNAMIC/STORE_ONLY',
  scope_snapshot JSON NOT NULL COMMENT '范围类型及资格规则；动态品牌不是固定门店名单',
  policy_versions JSON NOT NULL COMMENT '提交及批准检查版本',
  content_hash CHAR(64) NOT NULL COMMENT '规范化内容摘要',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,release_no),
  UNIQUE KEY uk_2 (tenant_no,activity_version_no),
  UNIQUE KEY uk_3 (tenant_no,activity_no,draft_revision_no),
  UNIQUE KEY uk_4 (tenant_no,issuer_no,activity_no,release_index),
  UNIQUE KEY uk_5 (tenant_no,legacy_plan_version_no),
  KEY ix_1 (tenant_no,issuer_no,state,starts_at),
  CHECK ((source_kind='V2_ACTIVITY' AND activity_no IS NOT NULL AND draft_revision_no IS NOT NULL) OR (source_kind='LEGACY_PLAN' AND legacy_plan_version_no IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · V2档位金额唯一真源，不在Core再维护一份
CREATE TABLE sv_release_tier (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  tier_no VARCHAR(64) NOT NULL COMMENT '档位号',
  release_no VARCHAR(64) NOT NULL COMMENT '冻结发行版本',
  ordinal INT NOT NULL COMMENT '显示次序',
  paid_amount_minor BIGINT NOT NULL COMMENT '实收',
  bonus_amount_minor BIGINT NOT NULL COMMENT '赠送',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,tier_no),
  UNIQUE KEY uk_2 (tenant_no,release_no,paid_amount_minor),
  UNIQUE KEY uk_3 (tenant_no,release_no,ordinal),
  CHECK (paid_amount_minor>0 AND bonus_amount_minor>=0),
  CHECK (paid_amount_minor<=9223372036854775807-bonus_amount_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 同事务额度计数器，按门店独立发行池及币种串行检查
CREATE TABLE sv_issue_quota (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  quota_no VARCHAR(64) NOT NULL COMMENT '额度池',
  store_no VARCHAR(64) NOT NULL COMMENT '单店发行门店',
  currency VARCHAR(8) NOT NULL COMMENT '币种',
  outstanding_minor BIGINT NOT NULL COMMENT '已到账未消费合计，冻结部分已含其中',
  reserved_minor BIGINT NOT NULL COMMENT '未到账但已占用发行额度',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,quota_no),
  UNIQUE KEY uk_2 (tenant_no,store_no,currency),
  CHECK (outstanding_minor>=0 AND reserved_minor>=0),
  CHECK (outstanding_minor<=9223372036854775807-reserved_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 原充值单额度占用及恢复栅栏
CREATE TABLE sv_issue_quota_reservation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  reservation_no VARCHAR(64) NOT NULL COMMENT '占用号',
  quota_no VARCHAR(64) NOT NULL COMMENT '额度池',
  recharge_no VARCHAR(64) NOT NULL COMMENT '原充值单',
  amount_minor BIGINT NOT NULL COMMENT '实收+赠送占额',
  status VARCHAR(24) NOT NULL COMMENT 'RESERVED/COMMITTED/RELEASED',
  policy_versions JSON NOT NULL COMMENT '办理检查版本',
  expires_at DATETIME(3) NULL COMMENT '未收款可释放；已收款未知不得TTL释放',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,reservation_no),
  UNIQUE KEY uk_2 (tenant_no,recharge_no),
  CHECK (amount_minor>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 顾客发行方目录，含零余额有历史账户
CREATE TABLE sv_customer_issuer_index (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  customer_no VARCHAR(64) NOT NULL COMMENT 'canonical顾客',
  issuer_no VARCHAR(64) NOT NULL COMMENT '发行方',
  account_no VARCHAR(64) NOT NULL COMMENT '账户',
  currency VARCHAR(8) NOT NULL COMMENT '币种',
  has_recharge TINYINT NOT NULL COMMENT '成功到账资格',
  last_recharge_at DATETIME(3) NULL COMMENT 'B端默认排序',
  last_activity_at DATETIME(3) NULL COMMENT 'C端最近储值或核销',
  last_occurrence_store_no VARCHAR(64) NULL COMMENT '最近实际发生门店',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,customer_no,issuer_no,currency),
  UNIQUE KEY uk_2 (tenant_no,account_no),
  KEY ix_1 (tenant_no,issuer_no,has_recharge,last_recharge_at,account_no),
  KEY ix_2 (tenant_no,customer_no,has_recharge,last_activity_at,issuer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 从已过账核销分配生成不可变清分事实，不执行打款
CREATE TABLE sv_clearing_entry (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  clearing_no VARCHAR(64) NOT NULL COMMENT '清分行',
  issuer_no VARCHAR(64) NOT NULL COMMENT '资金发行方',
  usage_no VARCHAR(64) NOT NULL COMMENT '原核销号',
  allocation_no VARCHAR(64) NOT NULL COMMENT '原批次分配引用',
  lot_no VARCHAR(64) NOT NULL COMMENT '资金批次',
  release_no VARCHAR(64) NOT NULL COMMENT '来源活动发行版本',
  issuer_version_no VARCHAR(64) NOT NULL COMMENT '原结算规则',
  recharge_store_no VARCHAR(64) NOT NULL COMMENT '原办理店',
  redemption_store_no VARCHAR(64) NOT NULL COMMENT '核销店',
  payer_entity_no VARCHAR(64) NOT NULL COMMENT '结算付款主体',
  payee_entity_no VARCHAR(64) NOT NULL COMMENT '服务收款主体',
  currency VARCHAR(8) NOT NULL COMMENT '币种',
  principal_used_minor BIGINT NOT NULL COMMENT '核销本金分量',
  bonus_used_minor BIGINT NOT NULL COMMENT '赠送分量',
  payable_minor BIGINT NOT NULL COMMENT '本规则应付金额，允许冲正为负',
  issuer_borne_minor BIGINT NOT NULL COMMENT '发行方承担分量',
  entry_type VARCHAR(24) NOT NULL COMMENT 'USE/REVERSAL/ADJUSTMENT',
  original_clearing_no VARCHAR(64) NULL COMMENT '冲正或调整的原行',
  decision_no VARCHAR(64) NULL COMMENT '退款／调整的已确认决定',
  occurred_at DATETIME(3) NOT NULL COMMENT '原账务事实时间',
  source_event_key VARCHAR(64) NOT NULL COMMENT '非空稳定事件摘要键，USE/usage/allocation或REVERSAL/decision/allocation',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,clearing_no),
  UNIQUE KEY uk_2 (tenant_no,usage_no,allocation_no,entry_type,decision_no),
  UNIQUE KEY uk_3 (tenant_no,source_event_key),
  KEY ix_1 (tenant_no,issuer_no,currency,occurred_at,clearing_no),
  KEY ix_2 (tenant_no,payee_entity_no,occurred_at,clearing_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 发行方账期对手主体结算，与按订单stl_bill分离
CREATE TABLE sv_settlement_bill (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  bill_no VARCHAR(64) NOT NULL COMMENT '业务单号',
  issuer_no VARCHAR(64) NOT NULL COMMENT '发行方',
  payer_entity_no VARCHAR(64) NOT NULL COMMENT '付款主体',
  payee_entity_no VARCHAR(64) NOT NULL COMMENT '收款主体',
  currency VARCHAR(8) NOT NULL COMMENT '币种',
  period_from DATETIME(3) NOT NULL COMMENT 'UTC起含',
  period_to DATETIME(3) NOT NULL COMMENT 'UTC止不含',
  bill_kind VARCHAR(24) NOT NULL COMMENT 'CROSS_ENTITY/INTERNAL_RECON',
  status VARCHAR(24) NOT NULL COMMENT 'DRAFT/PENDING_RECON/DISPUTED/PENDING_PAYMENT/PENDING_RECEIPT/SETTLED',
  base_payable_minor BIGINT NOT NULL COMMENT '原清分合计',
  adjustment_minor BIGINT NOT NULL COMMENT '有据调整合计',
  registered_payment_minor BIGINT NOT NULL COMMENT '未撤销已登记付款合计',
  confirmed_receipt_minor BIGINT NOT NULL COMMENT '收款方确认金额合计',
  cutoff_at DATETIME(3) NOT NULL COMMENT '生成截止水位',
  confirmed_by VARCHAR(64) NULL COMMENT '对账确认人',
  confirmed_at DATETIME(3) NULL COMMENT '对账时间',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,bill_no),
  UNIQUE KEY uk_2 (tenant_no,issuer_no,payer_entity_no,payee_entity_no,currency,period_from,period_to,bill_kind),
  KEY ix_1 (tenant_no,payer_entity_no,status,period_from,bill_no),
  KEY ix_2 (tenant_no,payee_entity_no,status,period_from,bill_no),
  CHECK (period_from<period_to),
  CHECK (registered_payment_minor>=0 AND confirmed_receipt_minor>=0),
  CHECK (confirmed_receipt_minor<=registered_payment_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 清分事实只进入一个正常结算账单
CREATE TABLE sv_settlement_bill_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  item_no VARCHAR(64) NOT NULL COMMENT '明细号',
  bill_no VARCHAR(64) NOT NULL COMMENT '账单',
  clearing_no VARCHAR(64) NOT NULL COMMENT '原清分行',
  amount_minor BIGINT NOT NULL COMMENT '原金额快照',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,item_no),
  UNIQUE KEY uk_2 (tenant_no,clearing_no),
  KEY ix_1 (tenant_no,bill_no,item_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 付款登记和收款确认分别记录，无银行执行器
CREATE TABLE sv_settlement_payment (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  payment_no VARCHAR(64) NOT NULL COMMENT '付款登记单',
  bill_no VARCHAR(64) NOT NULL COMMENT '账单',
  payer_entity_no VARCHAR(64) NOT NULL COMMENT '支付方',
  payee_entity_no VARCHAR(64) NOT NULL COMMENT '收款方',
  currency VARCHAR(8) NOT NULL COMMENT '币种',
  amount_minor BIGINT NOT NULL COMMENT '本次付款金额',
  paid_at DATETIME(3) NOT NULL COMMENT '实际付款时间',
  receipt_ciphertext VARBINARY(2048) NOT NULL COMMENT '凭据编号加密，列表脱敏',
  receipt_digest CHAR(64) NOT NULL COMMENT '主体/通道/编号规范化摘要',
  evidence_refs JSON NOT NULL COMMENT '私有授权附件引用',
  status VARCHAR(24) NOT NULL COMMENT 'REGISTERED/RECEIPT_CONFIRMED/VOIDED',
  registered_by VARCHAR(64) NOT NULL COMMENT '付款侧岗位',
  received_by VARCHAR(64) NULL COMMENT '收款侧岗位',
  received_at DATETIME(3) NULL COMMENT '实际到账确认',
  void_reason VARCHAR(255) NULL COMMENT '撤销原因；已确认不得直接撤销',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,payment_no),
  UNIQUE KEY uk_2 (tenant_no,payer_entity_no,receipt_digest),
  KEY ix_1 (tenant_no,bill_no,status,payment_no),
  CHECK (amount_minor>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue · 对账、争议、调整、付款确认的只追加审计事件
CREATE TABLE sv_settlement_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  event_no VARCHAR(64) NOT NULL COMMENT '事件号',
  bill_no VARCHAR(64) NOT NULL COMMENT '账单',
  event_type VARCHAR(24) NOT NULL COMMENT 'RECON_CONFIRM/DISPUTE/DISPUTE_RESOLVE/ADJUST/PAYMENT/RECEIPT',
  command_no VARCHAR(64) NOT NULL COMMENT '稳定幂等命令',
  event_snapshot JSON NOT NULL COMMENT '事件事实及引用，不存明文凭据',
  bill_revision BIGINT NOT NULL COMMENT '事件后的账单版本',
  reason VARCHAR(255) NULL COMMENT '争议或调整依据',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,event_no),
  UNIQUE KEY uk_2 (tenant_no,command_no),
  KEY ix_1 (tenant_no,bill_no,bill_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- pay/storedvalue/readmodel · 来源批次活动统计，不把共享账户余额复制到活动
CREATE TABLE sv_activity_daily_summary (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '内部主键',
  tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
  issuer_no VARCHAR(64) NOT NULL COMMENT '发行方',
  activity_no VARCHAR(64) NULL COMMENT 'V2的Core活动，历史PLAN为NULL',
  source_kind VARCHAR(24) NOT NULL COMMENT 'V2_ACTIVITY/LEGACY_PLAN',
  release_no VARCHAR(64) NOT NULL COMMENT '发行版本',
  occurrence_store_no VARCHAR(64) NOT NULL COMMENT '实际发生门店',
  currency VARCHAR(8) NOT NULL COMMENT '币种',
  business_date DATE NOT NULL COMMENT '发行方时区日期',
  credited_paid_minor BIGINT NOT NULL COMMENT '当日入账实收',
  credited_bonus_minor BIGINT NOT NULL COMMENT '入账赠送',
  used_principal_minor BIGINT NOT NULL COMMENT '按本活动批次核销本金',
  used_bonus_minor BIGINT NOT NULL COMMENT '按本活动批次核销赠金',
  reversed_minor BIGINT NOT NULL COMMENT '冲正额',
  clearing_minor BIGINT NOT NULL COMMENT '清分金额',
  recharge_count BIGINT NOT NULL COMMENT '笔数',
  redemption_count BIGINT NOT NULL COMMENT '笔数',
  projection_watermark VARCHAR(64) NOT NULL COMMENT '投影水位／对账批次',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '金融事实禁止物理删除；唯一性不随软删除释放',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NOT NULL COMMENT '认证操作者',
  updated_by VARCHAR(64) NOT NULL COMMENT '认证修改人',
  UNIQUE KEY uk_1 (tenant_no,issuer_no,release_no,occurrence_store_no,currency,business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 以下仅增量结构草案；不能跳过设计文档中的迁移/索引切换前置条件。
ALTER TABLE pmt_activity ADD COLUMN management_no VARCHAR(64) NULL COMMENT '经营授权范围';
ALTER TABLE pmt_activity ADD COLUMN owner_scope_type VARCHAR(24) NOT NULL DEFAULT 'LEGACY_ENTITY' COMMENT 'LEGACY_ENTITY/BRAND/STORE';
ALTER TABLE pmt_activity ADD COLUMN owner_scope_no VARCHAR(64) NULL COMMENT '品牌或门店';
ALTER TABLE pmt_activity ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT 'Pay发行方';
ALTER TABLE sv_account ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '迁移完成后非空';
ALTER TABLE sv_account ADD COLUMN issuer_version_no VARCHAR(64) NULL COMMENT '开户时配置快照';
ALTER TABLE sv_recharge ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '原发行方';
ALTER TABLE sv_recharge ADD COLUMN issuer_version_no VARCHAR(64) NULL COMMENT '冻结收款/清分版本';
ALTER TABLE sv_recharge ADD COLUMN release_no VARCHAR(64) NULL COMMENT '新发行版本';
ALTER TABLE sv_recharge ADD COLUMN tier_no VARCHAR(64) NULL COMMENT '新档位';
ALTER TABLE sv_recharge ADD COLUMN collection_entity_no VARCHAR(64) NULL COMMENT '实际收款主体';
ALTER TABLE sv_recharge ADD COLUMN settlement_entity_no VARCHAR(64) NULL COMMENT '指定结算主体';
ALTER TABLE sv_recharge ADD COLUMN quota_reservation_no VARCHAR(64) NULL COMMENT '单店额度占位';
ALTER TABLE sv_lot ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '发行方';
ALTER TABLE sv_lot ADD COLUMN issuer_version_no VARCHAR(64) NULL COMMENT '冻结结算规则';
ALTER TABLE sv_lot ADD COLUMN release_no VARCHAR(64) NULL COMMENT '来源发行版本';
ALTER TABLE sv_lot ADD COLUMN tier_no VARCHAR(64) NULL COMMENT '来源档位';
ALTER TABLE sv_lot ADD COLUMN collection_entity_no VARCHAR(64) NULL COMMENT '原实际收款主体';
ALTER TABLE sv_lot ADD COLUMN settlement_entity_no VARCHAR(64) NULL COMMENT '原结算主体';
ALTER TABLE sv_statement ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '账务范围';
ALTER TABLE sv_statement ADD COLUMN scope_type VARCHAR(24) NULL COMMENT 'BRAND/STORE';
ALTER TABLE sv_statement ADD COLUMN scope_no VARCHAR(64) NULL COMMENT '归属品牌或门店';
ALTER TABLE sv_statement ADD COLUMN release_no VARCHAR(64) NULL COMMENT '充值来源，混合批次核销使用分配表归集';
ALTER TABLE sv_statement ADD COLUMN operating_entity_no VARCHAR(64) NULL COMMENT '发生时经营主体';
ALTER TABLE sv_statement ADD COLUMN settlement_entity_no VARCHAR(64) NULL COMMENT '原结算主体';
ALTER TABLE sv_voucher ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '跟账户范围一致';
ALTER TABLE sv_recharge_plan ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '旧单店方案到单店发行方映射';
ALTER TABLE sv_recharge_plan ADD COLUMN migrated_release_no VARCHAR(64) NULL COMMENT '历史版本兼容发行配置';
ALTER TABLE sv_hold_allocation ADD COLUMN allocation_no VARCHAR(64) NULL COMMENT '可追溯稳定分配号';
ALTER TABLE sv_hold_allocation ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '明确单账户发行方';
ALTER TABLE sv_hold_allocation ADD COLUMN release_no VARCHAR(64) NULL COMMENT '来源批次版本';
ALTER TABLE ord_ent_service_order ADD COLUMN selected_issuer_no VARCHAR(64) NULL COMMENT '预览及授权绑定的发行方';
ALTER TABLE ord_ent_service_order ADD COLUMN selected_account_no VARCHAR(64) NULL COMMENT '内部稳定账户引用；客户端仍用opaque引用';
ALTER TABLE mbr_identity_ticket ADD COLUMN operator_scope_type VARCHAR(24) NULL COMMENT 'STORE/BRAND_VIEW';
ALTER TABLE mbr_identity_ticket ADD COLUMN issuer_no VARCHAR(64) NULL COMMENT '办理指定资金范围';
ALTER TABLE mbr_identity_ticket ADD COLUMN brand_scope_revision BIGINT NULL COMMENT '识别时品牌版本';

-- CONDITIONAL CUTOVER (requires checked backfill and coordinated service release):
-- sv_account: make customer_no and issuer_no NOT NULL only after conflicts/unknown identities are resolved.
-- sv_account: add UNIQUE(tenant_no,issuer_no,customer_no,currency), then drop uk_sv_owner and uk_sv_customer_owner.
-- sv_account: brand rows need store_no nullable; entity_no remains immutable legacy attribution, not authorization scope.
-- pmt_activity: allow entity_no NULL for new BRAND-owned activities; never put settlement_entity_no here as a fake owner.
-- Legacy activity endpoints must explicitly filter LEGACY_ENTITY or STORE, never authorize BRAND by entity_no.
-- sv_recharge: extend source CHECK with ISSUER_RELEASE => release_no/tier_no required, no fabricated reservation.
-- Existing financial entity/store facts are not rewritten; issuers and releases are mapped in an audited manifest.
-- Add issuer scope indexes and hold/operation/account consistency constraints at cutover as documented.

-- PAYMENT MERCHANT CLARIFICATION
-- Reuse mch_payment_merchant(entity_no,pay_merchant_no,pay_channel), do not create a second merchant table.
ALTER TABLE sv_recharge ADD COLUMN collection_pay_merchant_no VARCHAR(64) NULL COMMENT '实际渠道收款商户，归属collection_entity_no；线下可空';
ALTER TABLE sv_recharge ADD COLUMN collection_pay_channel VARCHAR(24) NULL COMMENT '实际收款渠道；不能改变顾客钱包归属';
ALTER TABLE sv_recharge ADD COLUMN collection_payment_no VARCHAR(64) NULL COMMENT '现有支付流水内部引用，服务端核验，不信任客户端自报渠道流水';
ALTER TABLE sv_lot ADD COLUMN collection_pay_merchant_no VARCHAR(64) NULL COMMENT '实际渠道收款商户，归属collection_entity_no；线下可空';
ALTER TABLE sv_lot ADD COLUMN collection_pay_channel VARCHAR(24) NULL COMMENT '实际收款渠道；不能改变顾客钱包归属';
