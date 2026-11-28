-- 设计草案之外、代码需要的补充。同时是给 n_platform 的修改建议，尚未在任何正式库执行。
-- 依据：TDD-登录与账号对齐平台基础库 §2.4、TDD-平台基础库换表 §2。

-- 会话与票据：按令牌摘要查会话要求至多一行、并按它加行锁串行；票据核销要求恰好更新一行。
-- 同一份建表语句里 ops_session 是好的，这几张更像是生成时出的错。
ALTER TABLE `usr_session` DROP INDEX `uk_usr_session_token`;
ALTER TABLE `usr_session` ADD UNIQUE KEY `uk_usr_session_token_hash` (`token_hash`);
ALTER TABLE `mch_session` DROP INDEX `uk_mch_session_token`;
ALTER TABLE `mch_session` ADD UNIQUE KEY `uk_mch_session_token_hash` (`token_hash`);
ALTER TABLE `auth_ticket` DROP INDEX `uk_login_ticket_hash`;
ALTER TABLE `auth_ticket` ADD UNIQUE KEY `uk_auth_ticket_ticket_hash` (`ticket_hash`);
ALTER TABLE `usr_guest_session` ADD UNIQUE KEY `uk_usr_guest_session_token_hash` (`token_hash`);
ALTER TABLE `usr_guest_session` ADD UNIQUE KEY `uk_usr_guest_session_session_no` (`session_no`);

-- 主体：名为 uk_ 的两个其实是普通索引。主体号是全库的业务主键（所有权校验要求按它恰好一行），
-- 店铺码是对外展示与扫码的唯一码；建档的幂等也靠撞唯一键。TDD-平台基础库换表「共性问题 · 唯一键」。
ALTER TABLE `mch_entity` DROP INDEX `uk_entity_no`;
ALTER TABLE `mch_entity` ADD UNIQUE KEY `uk_mch_entity_entity_no` (`entity_no`);
ALTER TABLE `mch_entity` DROP INDEX `uk_store_code`;
ALTER TABLE `mch_entity` ADD UNIQUE KEY `uk_mch_entity_store_code` (`store_code`);

-- 租户号：代码插入时不给，靠库上的默认值 MAIN（旧表都有）。新表没有默认值，插入会失败或写成空。
ALTER TABLE `mch_group` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_entity` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_qualification` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_category_license` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_device` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_fulfillment` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_service_area` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_brand` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_brand_store_binding` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_personnel` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_personnel_store` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_personnel_position` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_account` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_pos_staff_pin` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `auth_ticket` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_role` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_role_point` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_grant` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_end_grant` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `agr_agreement_version` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `agr_agreement_consent` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_person` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_account` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_identity` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_guest_session` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `ops_staff` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';

-- 角色：预置角色只由种子写入，代码建的都是商家自定义角色。旧表 builtin 默认 0，新表没有默认值，不写就插入失败。
ALTER TABLE `mch_role` ALTER COLUMN `builtin` SET DEFAULT 0;

-- 主体：建档时代码只写自己关心的列，其余靠旧表的默认值（资金模式聚合、履约能力自提、市场等）。
-- 新表这些列都没有默认值，不补的话新建的主体读出来是空，下游按空判成别的分支。取值与旧表一致。
ALTER TABLE `mch_entity` ALTER COLUMN `verified` SET DEFAULT 0;
ALTER TABLE `mch_entity` ALTER COLUMN `breach_count` SET DEFAULT 0;
ALTER TABLE `mch_entity` ALTER COLUMN `status` SET DEFAULT 'ACTIVE';
ALTER TABLE `mch_entity` ALTER COLUMN `service_scope` SET DEFAULT 'COMMUNITY';
ALTER TABLE `mch_entity` ALTER COLUMN `points_enabled` SET DEFAULT 0;
ALTER TABLE `mch_entity` ALTER COLUMN `points_forced` SET DEFAULT 0;
ALTER TABLE `mch_entity` ALTER COLUMN `fulfillment_reach` SET DEFAULT 'PICKUP';
ALTER TABLE `mch_entity` ALTER COLUMN `funds_mode` SET DEFAULT 'AGGREGATED';
ALTER TABLE `mch_entity` ALTER COLUMN `is_agri_producer` SET DEFAULT 0;
ALTER TABLE `mch_entity` ALTER COLUMN `biz_qualification` SET DEFAULT 'UNREGISTERED';
ALTER TABLE `mch_entity` ALTER COLUMN `market` SET DEFAULT 'CN';
ALTER TABLE `mch_entity` ALTER COLUMN `self_operated` SET DEFAULT 0;

-- 门店：同主体那两处——名为 uk_ 的三个是普通索引（门店号是全库业务主键，店铺码、短链各自唯一）；
-- 建店时代码只写自己关心的列，其余靠旧表默认值（配送半径 3000 米、经营模式自营、评分从 0 起等），新表都没有。取值与旧表一致。
ALTER TABLE `mch_store` DROP INDEX `uk_store_no`;
ALTER TABLE `mch_store` ADD UNIQUE KEY `uk_mch_store_store_no` (`store_no`);
ALTER TABLE `mch_store` DROP INDEX `uk_mch_store_code`;
ALTER TABLE `mch_store` ADD UNIQUE KEY `uk_mch_store_store_code` (`store_code`);
ALTER TABLE `mch_store` DROP INDEX `uk_mch_store_slug`;
ALTER TABLE `mch_store` ADD UNIQUE KEY `uk_mch_store_slug_unique` (`slug`);
ALTER TABLE `mch_store` ALTER COLUMN `is_default` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `status` SET DEFAULT 'ACTIVE';
ALTER TABLE `mch_store` ALTER COLUMN `delivery_radius_m` SET DEFAULT 3000;
ALTER TABLE `mch_store` ALTER COLUMN `delivery_min_order_minor` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `delivery_fee_minor` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `delivery_free_threshold_minor` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `business_mode` SET DEFAULT 'SELF_OPERATED';
ALTER TABLE `mch_store` ALTER COLUMN `plan_suspended` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `rating` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `rating_count` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `score_goods` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `score_service` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `score_speed` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `address_verified` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `offline_pay_enabled` SET DEFAULT 0;
ALTER TABLE `mch_store` ALTER COLUMN `cod_enabled` SET DEFAULT 0;

-- 人员：业务键（主体内人员号）是普通索引；账号关联并进人员后（第 2 步 D），一个账号只关联一个人员，原表有唯一键，补上。
-- 未关联时 mch_account_no 为 NULL，唯一键不拦多个 NULL。TDD-平台基础库换表「共性问题 · 唯一键」。
ALTER TABLE `mch_personnel` DROP INDEX `uk_mch_personnel_1`;
ALTER TABLE `mch_personnel` ADD UNIQUE KEY `uk_mch_personnel_personnel_no` (`tenant_no`, `entity_no`, `personnel_no`);
ALTER TABLE `mch_personnel` ADD UNIQUE KEY `uk_mch_personnel_account` (`tenant_no`, `entity_no`, `mch_account_no`);

-- 门店服务范围（第 2 步 E，TDD-平台基础库换表「E 服务范围：channel 的定义与方案」，11-28 用户定）：
-- channel 用空串表示「对本店所有履约方式生效」（基础覆盖面），收窄行填具体方式、自提点行填 NEIGHBOR_PICKUP；
-- 空串而不是 NULL，唯一键才拦得住重复（与授权表 mch_grant 的空串哨兵同一做法）。
-- area_no 全库唯一（审核单靠它指回本行）；一店一方式同一对象只一行（「不限」每店一行）。名为 uk_ 的两个原是普通索引。
-- 默认值与旧表一致：来源商家自选、生效、纳入。
ALTER TABLE `mch_store_service_area` MODIFY COLUMN `channel` varchar(24) NOT NULL DEFAULT '' COMMENT '只对哪种履约方式生效：空串 = 对本店所有方式生效（基础覆盖面，排除与不限只写在这一层）；填了方式 = 该方式在「部分」口径下的收窄子集；NEIGHBOR_PICKUP 配 PICKUP_POINT = 邻里自提点';
ALTER TABLE `mch_store_service_area` DROP INDEX `uk_service_area_no`;
ALTER TABLE `mch_store_service_area` ADD UNIQUE KEY `uk_store_service_area_no` (`area_no`);
ALTER TABLE `mch_store_service_area` DROP INDEX `uk_service_area_store`;
ALTER TABLE `mch_store_service_area` ADD UNIQUE KEY `uk_store_service_area_item` (`tenant_no`, `store_no`, `channel`, `level`, `ref_code`);
ALTER TABLE `mch_store_service_area` ALTER COLUMN `source` SET DEFAULT 'SELF';
ALTER TABLE `mch_store_service_area` ALTER COLUMN `status` SET DEFAULT 'ACTIVE';
ALTER TABLE `mch_store_service_area` ALTER COLUMN `mode` SET DEFAULT 'INCLUDE';

-- 多边形范围的网格派生表（Q5：补表）。由多边形范围行（含收窄拷贝）重建，可达命中先查网格、边界格再精确判。
-- channel 跟着所属范围行冗余，命中时按它分到基础面或某方式的收窄子集。
CREATE TABLE `mch_service_area_cell` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) NOT NULL DEFAULT 'MAIN' COMMENT '租户号',
  `area_no` varchar(32) NOT NULL COMMENT '所属范围项号（mch_store_service_area.area_no）',
  `entity_no` varchar(32) NOT NULL COMMENT '主体号，冗余',
  `store_no` varchar(32) NOT NULL COMMENT '门店号，冗余',
  `channel` varchar(24) NOT NULL DEFAULT '' COMMENT '所属范围行的 channel，冗余（空串 = 基础覆盖面）',
  `mode` varchar(16) NOT NULL COMMENT '所属范围行的覆盖方向 INCLUDE / EXCLUDE，冗余',
  `cell_id` varchar(32) NOT NULL COMMENT 'S2 网格号',
  `s2_level` tinyint NOT NULL COMMENT 'S2 层级',
  `boundary` tinyint NOT NULL DEFAULT 0 COMMENT '1 = 边界格，命中后还要按多边形精确判',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 = 正常，1 = 已删除',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次修改加 1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(32) DEFAULT NULL COMMENT '创建人',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，每次修改自动更新',
  `updated_by` varchar(32) DEFAULT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sac_area_cell` (`area_no`, `cell_id`),
  KEY `idx_sac_cell` (`cell_id`)
) COMMENT='门店服务范围的多边形网格：由多边形范围行派生，可达判定按网格命中';

-- 授权（第 2 步 G，TDD-平台基础库换表 Q7 与「G 授权合并：落地方案与待定项」，11-28 用户定）：
-- 门店角色与管理授权并成一张。Q7.6 去重：同一集团、被授权人、范围、类型、角色码或权限码只一条，
-- ROLE 行权限码、PERMISSION 行角色码写空串哨兵；授权号唯一。名为 uk_ 的两个原是普通索引。
-- G1：操作人编号带前缀（MCH: / USER: + 32 位号），actor_no、owner_actor_no 与这些表的审计列放宽到 64。
ALTER TABLE `mch_grant` MODIFY COLUMN `actor_no` varchar(64) NOT NULL COMMENT '被授权人：B 端账号号或 C 端用户号，带前缀（MCH: / USER:）';
ALTER TABLE `mch_grant` MODIFY COLUMN `role_code` varchar(32) NOT NULL DEFAULT '' COMMENT '授予的角色码（grant_kind=ROLE 时有值，PERMISSION 行为空串）';
ALTER TABLE `mch_grant` MODIFY COLUMN `permission_code` varchar(32) NOT NULL DEFAULT '' COMMENT '授予的权限码（grant_kind=PERMISSION 时有值，ROLE 行为空串）';
ALTER TABLE `mch_grant` MODIFY COLUMN `created_by` varchar(64) DEFAULT NULL COMMENT '创建人（带前缀的操作人编号）';
ALTER TABLE `mch_grant` MODIFY COLUMN `updated_by` varchar(64) DEFAULT NULL COMMENT '更新人（带前缀的操作人编号）';
ALTER TABLE `mch_grant` ALTER COLUMN `delegatable` SET DEFAULT 0;
ALTER TABLE `mch_grant` DROP INDEX `uk_1`;
ALTER TABLE `mch_grant` ADD UNIQUE KEY `uk_mch_grant_no` (`tenant_no`, `grant_no`);
ALTER TABLE `mch_grant` DROP INDEX `uk_2`;
ALTER TABLE `mch_grant` ADD UNIQUE KEY `uk_mch_grant_item` (`tenant_no`, `management_no`, `actor_no`, `resource_type`, `resource_no`, `grant_kind`, `role_code`, `permission_code`);
ALTER TABLE `mch_group` MODIFY COLUMN `owner_actor_no` varchar(64) NOT NULL COMMENT '集团的有效所有人（老板）：带前缀的操作人编号（MCH: / USER:）';
ALTER TABLE `mch_group` MODIFY COLUMN `created_by` varchar(64) DEFAULT NULL COMMENT '创建人（带前缀的操作人编号）';
ALTER TABLE `mch_group` MODIFY COLUMN `updated_by` varchar(64) DEFAULT NULL COMMENT '更新人（带前缀的操作人编号）';
ALTER TABLE `mch_group` ALTER COLUMN `auth_revision` SET DEFAULT 0;
ALTER TABLE `mch_brand` MODIFY COLUMN `created_by` varchar(64) DEFAULT NULL COMMENT '创建人（带前缀的操作人编号）';
ALTER TABLE `mch_brand` MODIFY COLUMN `updated_by` varchar(64) DEFAULT NULL COMMENT '更新人（带前缀的操作人编号）';
ALTER TABLE `mch_brand_store_binding` MODIFY COLUMN `created_by` varchar(64) DEFAULT NULL COMMENT '创建人（带前缀的操作人编号）';
ALTER TABLE `mch_brand_store_binding` MODIFY COLUMN `updated_by` varchar(64) DEFAULT NULL COMMENT '更新人（带前缀的操作人编号）';

-- 储值开通后的门店入退连锁（G4：保留这张表，只装与支付侧的两阶段流程：准备 → 支付已准备 → 完成 / 驳回）。
-- 「一店同时只一单在途」原靠生成列加唯一键；新库各表不用生成列，in_flight_store_no 由代码在在途时写门店号、结束时清空。
CREATE TABLE `mch_brand_financial_change` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) NOT NULL DEFAULT 'MAIN' COMMENT '租户号',
  `change_no` varchar(32) NOT NULL COMMENT '变更单号',
  `management_no` varchar(32) NOT NULL COMMENT '商户集团号',
  `store_no` varchar(32) NOT NULL COMMENT '门店号',
  `from_brand_no` varchar(32) DEFAULT NULL COMMENT '原品牌号（入连锁时为空）',
  `to_brand_no` varchar(32) DEFAULT NULL COMMENT '新品牌号（退连锁时为空）',
  `original_actor_no` varchar(64) NOT NULL COMMENT '发起人（带前缀的操作人编号）',
  `command_key` varchar(64) NOT NULL COMMENT '发起命令的幂等键',
  `command_hash` char(64) NOT NULL COMMENT '发起命令内容摘要',
  `source_snapshot` json NOT NULL COMMENT '发起时冻结的成员事实',
  `pay_prepare_snapshot` json DEFAULT NULL COMMENT '支付侧准备回执',
  `status` varchar(16) NOT NULL COMMENT '状态：PREPARING 准备中 / PAY_PREPARED 支付已准备 / COMPLETED 完成 / REJECTED 驳回',
  `in_flight_store_no` varchar(32) DEFAULT NULL COMMENT '在途（准备中、支付已准备）时为门店号，结束时清空；唯一键保证一店同时只一单在途',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 = 正常，1 = 已删除',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次修改加 1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建人（带前缀的操作人编号）',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，每次修改自动更新',
  `updated_by` varchar(64) DEFAULT NULL COMMENT '更新人（带前缀的操作人编号）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_brand_financial_change` (`tenant_no`, `change_no`),
  UNIQUE KEY `uk_brand_financial_change_command` (`tenant_no`, `original_actor_no`, `command_key`),
  UNIQUE KEY `uk_brand_financial_change_store` (`tenant_no`, `in_flight_store_no`)
) COMMENT='储值开通后的门店入退连锁：与支付侧两阶段变更成员的中间状态与回执';
