-- P3 compatible dimensions only. No backfill or legacy unique-key removal in Flyway.
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;

ALTER TABLE sv_issuer ADD COLUMN write_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY';

ALTER TABLE sv_account ADD COLUMN issuer_no VARCHAR(64) NULL;

ALTER TABLE sv_account ADD COLUMN issuer_version_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge ADD COLUMN issuer_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge ADD COLUMN issuer_version_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge ADD COLUMN release_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge ADD COLUMN tier_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge ADD COLUMN collection_entity_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge ADD COLUMN settlement_entity_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge ADD COLUMN quota_reservation_no VARCHAR(64) NULL;

ALTER TABLE sv_lot ADD COLUMN issuer_no VARCHAR(64) NULL;

ALTER TABLE sv_lot ADD COLUMN issuer_version_no VARCHAR(64) NULL;

ALTER TABLE sv_lot ADD COLUMN release_no VARCHAR(64) NULL;

ALTER TABLE sv_lot ADD COLUMN tier_no VARCHAR(64) NULL;

ALTER TABLE sv_lot ADD COLUMN collection_entity_no VARCHAR(64) NULL;

ALTER TABLE sv_lot ADD COLUMN settlement_entity_no VARCHAR(64) NULL;

ALTER TABLE sv_statement ADD COLUMN issuer_no VARCHAR(64) NULL;

ALTER TABLE sv_statement ADD COLUMN scope_no VARCHAR(64) NULL;

ALTER TABLE sv_statement ADD COLUMN release_no VARCHAR(64) NULL;

ALTER TABLE sv_statement ADD COLUMN operating_entity_no VARCHAR(64) NULL;

ALTER TABLE sv_statement ADD COLUMN settlement_entity_no VARCHAR(64) NULL;

ALTER TABLE sv_statement ADD COLUMN scope_type VARCHAR(24) NULL;

ALTER TABLE sv_voucher ADD COLUMN issuer_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge_plan ADD COLUMN issuer_no VARCHAR(64) NULL;

ALTER TABLE sv_recharge_plan ADD COLUMN migrated_release_no VARCHAR(64) NULL;

ALTER TABLE sv_hold_allocation ADD COLUMN issuer_no VARCHAR(64) NULL;

ALTER TABLE sv_hold_allocation ADD COLUMN allocation_no VARCHAR(64) NULL;

ALTER TABLE sv_hold_allocation ADD COLUMN release_no VARCHAR(64) NULL;
