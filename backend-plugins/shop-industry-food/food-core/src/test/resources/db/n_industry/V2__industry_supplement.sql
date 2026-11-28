-- 行业库 n_industry 测试补充脚本（TDD-餐饮域迁移新库 P1-1）。在 V1（设计 DDL）之后执行，只用于餐饮测试新建的一次性行业库。
-- 每一段写明依据；结构取自餐饮现有表（私有迁移 V1–V37 的结果），不改设计文档（「清单结论先记后改」）。

-- 一、厨显：沿用 .61 已执行的 deploy/ume/food-kds-cutover/01-industry-kds.sql（游标、语音命令、工位运行保留独立表，补订阅的请求防重列与唯一键）。
-- Run once on the target industry schema before importing legacy KDS data.
-- Keep the target surrogate keys and retain the runtime's original business keys.
ALTER TABLE fnb_station
    MODIFY tenant_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
    MODIFY entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY name VARCHAR(64) NOT NULL,
    MODIFY station_type VARCHAR(24) NOT NULL,
    MODIFY enabled TINYINT NOT NULL,
    MODIFY published_revision_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    MODIFY created_at DATETIME(3) NOT NULL,
    MODIFY updated_at DATETIME(3) NOT NULL,
    MODIFY created_by VARCHAR(32) NOT NULL,
    MODIFY updated_by VARCHAR(32) NOT NULL,
    MODIFY version BIGINT NOT NULL DEFAULT 0,
    MODIFY runtime_status VARCHAR(10) NULL COMMENT '当前运行状态：RUNNING 出品中、PAUSED 暂停；空即 RUNNING（开停写在工位上，D-2）',
    DROP INDEX uk_food_1,
    ADD UNIQUE KEY uk_food_1 (station_no);

-- 厨显事件流退役（TDD §2.8 D-3）：屏收刷新信号后拉当前状态，不再按门店序号拉事件；设计稿建的表删掉，游标表不建。
DROP TABLE fnb_kds_event;

-- 门店设备（TDD §2.8 D-5，用户 2026-11-28 定）：由平台库设计稿的 mch_store_device 搬来，屏类设备归行业库。
-- 打印机、本地打印代理留在打印域，支付终端留在支付库；不设通用 JSON 列。屏用店员账号登录，设备只需在册且在用；
-- 配对、凭据与离线收银几列先留空，做设备凭据登录或离线收银时再用。
CREATE TABLE `ind_store_device` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '租户号',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '设备所属主体号',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '设备所在门店号',
  `device_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '设备号',
  `device_type` varchar(16) NOT NULL COMMENT '设备类型：ORDER_SCREEN 点餐屏、KDS 厨显、CALL_SCREEN 叫号屏、OFFLINE_POS 离线收银',
  `display_name` varchar(64) NOT NULL COMMENT '设备显示名，如「热菜厨显 1 号」',
  `serial_no` varchar(32) DEFAULT NULL COMMENT '设备硬件序列号或厂商机身号',
  `platform` varchar(24) DEFAULT NULL COMMENT '运行平台：WINDOWS、ANDROID、LINUX、MACOS',
  `software_version` varchar(32) DEFAULT NULL COMMENT '设备上运行的软件版本',
  `status` varchar(10) NOT NULL COMMENT '设备状态：PAIRING 配对中、ACTIVE 在用、SUSPENDED 停用、REVOKED 已吊销',
  `generation` bigint NOT NULL DEFAULT 0 COMMENT '设备代数，重新配对或换机时递增',
  `credential_hash` varbinary(64) DEFAULT NULL COMMENT '设备访问凭据的哈希（设备凭据登录时用）',
  `credential_version` int DEFAULT NULL COMMENT '设备凭据版本，轮换时递增',
  `enrollment_expires_at` datetime(3) DEFAULT NULL COMMENT '配对码有效期',
  `signer_key_no` varchar(32) DEFAULT NULL COMMENT '离线收银签名密钥号',
  `signer_public_key` varchar(128) DEFAULT NULL COMMENT '离线收银签名公钥',
  `signer_public_key_hash` char(64) DEFAULT NULL COMMENT '签名公钥摘要',
  `challenge_no` varchar(32) DEFAULT NULL COMMENT '联网后核验挑战号',
  `challenge_expires_at` datetime(3) DEFAULT NULL COMMENT '核验挑战的过期时间',
  `write_lease_no` varchar(32) DEFAULT NULL COMMENT '离线写入授权（租约）号',
  `write_lease_expires_at` datetime(3) DEFAULT NULL COMMENT '离线写入授权到期时间',
  `write_accepted_through` bigint DEFAULT NULL COMMENT '已被接收的离线单据序号上限',
  `last_seen_at` datetime(3) DEFAULT NULL COMMENT '最后在线时间',
  `revoked_at` datetime(3) DEFAULT NULL COMMENT '吊销时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
  `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次修改加 1',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `created_by` varchar(32) NOT NULL COMMENT '创建人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  `updated_by` varchar(32) NOT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_device_no` (`device_no`),
  KEY `ix_store_device_store` (`entity_no`, `store_no`, `device_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店设备：点餐屏、厨显、叫号屏、离线收银的登记与状态；屏用店员账号登录，设备在册且在用才能连';

-- 厨显订阅精简（TDD §2.8 D-5）：只记「哪台厨显看哪些工位」，每台一行；登录人、到期、防重、撤销都去掉，停用设备即撤销。
DROP TABLE fnb_kds_subscription;
CREATE TABLE `fnb_kds_subscription` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '租户号',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属主体编号',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '门店编号',
  `device_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '厨显设备号，见 ind_store_device',
  `stations` json NOT NULL COMMENT '这台厨显看的工位编号列表',
  `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次修改加 1',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `created_by` varchar(32) NOT NULL COMMENT '创建人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  `updated_by` varchar(32) NOT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kds_subscription_device` (`device_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='厨显订阅：哪台厨显看哪些工位';

-- 工位运行状态表 fnb_station_runtime 不建（TDD §2.8 D-2）：开停写 fnb_station.runtime_status（RUNNING/PAUSED，空即 RUNNING），用工位版本号。

CREATE TABLE fnb_kds_voice_command (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 command_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 action VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL,
 source_version BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 device_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 voice_text VARCHAR(128) NULL,
 requested_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 claim_actor VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 lease_token VARCHAR(64) NULL,
 lease_until DATETIME(3) NULL,
 response_code VARCHAR(64) NULL,
 PRIMARY KEY(command_no),
 UNIQUE KEY uk_kds_voice_key(entity_no,store_no,requested_by,request_key),
 KEY ix_kds_voice_delivery(entity_no,store_no,device_no,status,created_at)
) ENGINE=InnoDB;

-- 二、ADR-068 第 4 条（防重列与唯一约束保留）：代码在用、设计稿去掉了的防重列。
ALTER TABLE fnb_kitchen_ticket ADD COLUMN dispatch_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '下达防重键（接单事件、定时出品）', ADD UNIQUE KEY uk_kitchen_ticket_dispatch_key (dispatch_key);
-- 合并 ⑤ 定时出品 → 出品单（TDD §2.5）：next_attempt_at 是定时下达的认领租约与重试时刻，代码在用；空表示无待下达。
ALTER TABLE fnb_kitchen_ticket ADD COLUMN next_attempt_at DATETIME(3) NULL COMMENT '定时下达的下次尝试时刻（认领租约、失败重试），空为无待下达', ADD KEY ix_kitchen_ticket_dispatch_due (next_attempt_at, id);
ALTER TABLE fnb_queue_ticket ADD COLUMN request_key CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '取号请求防重键', ADD COLUMN request_hash CHAR(64) NULL COMMENT '取号请求摘要', ADD COLUMN seat_request_hash CHAR(64) NULL COMMENT '入座请求摘要', ADD UNIQUE KEY uk_queue_ticket_request (entity_no, store_no, request_key);
ALTER TABLE fnb_custody_movement ADD COLUMN key_hash VARCHAR(64) NOT NULL COMMENT '寄存动作防重键摘要', ADD COLUMN request_hash VARCHAR(64) NOT NULL COMMENT '寄存动作请求摘要', ADD UNIQUE KEY uk_custody_movement_key (tenant_no, entity_no, store_no, key_hash);

-- 设计缺陷修正：ind_store_policy 餐饮与美业共用，设计说明写「门店 + 行业唯一」，DDL 的唯一键漏了行业（TDD §2.5，先记后改）。
ALTER TABLE ind_store_policy DROP INDEX uk_bty_store_policy_2, ADD UNIQUE KEY uk_ind_store_policy_store_industry (tenant_no, entity_no, store_no, industry_code);

-- 三、ADR-068 修订一：制作任务归行业库（设计 DDL 里原拟并入 n_trade.ord_execution）。
CREATE TABLE `fnb_kitchen_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntity自增物理主键，不对外不进URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿用BaseEntity，一期MAIN；不是entity_no，不另启租户体系',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '当前主体或业务发生时主体快照，不等于账号和支付商户号',
  `created_at` datetime(3) NOT NULL COMMENT '沿用MetaObjectHandler及现有LocalDateTime持久化约定',
  `created_by` varchar(32) NOT NULL COMMENT '当前认证业务键；按现有填充规则，作业SYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT '沿用公共填充',
  `updated_by` varchar(32) NOT NULL COMMENT '沿用公共填充',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntity乐观锁',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntity逻辑删除；签核和事实表禁止业务归档覆盖历史',
  `task_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '任务号',
  `ticket_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '下达单',
  `food_line_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '菜品行',
  `component_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '套餐组件',
  `station_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '工位',
  `route_version` bigint NOT NULL COMMENT '路由版本',
  `qty` decimal(18,6) NOT NULL COMMENT '目标量',
  `making_qty` decimal(18,6) NOT NULL COMMENT '制作量',
  `ready_qty` decimal(18,6) NOT NULL COMMENT '出品量',
  `served_qty` decimal(18,6) NOT NULL COMMENT '上菜量',
  `void_qty` decimal(18,6) NOT NULL COMMENT '撤销量',
  `status` varchar(16) NOT NULL COMMENT 'HELD/QUEUED/MAKING/READY/SERVED/VOIDED',
  `task_part_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '非空源行/组件稳定键',
  `task_role` varchar(16) NOT NULL COMMENT 'MAKE / ASSEMBLE / PACK',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`task_no`),
  UNIQUE KEY `uk_food_2` (`ticket_no`,`task_part_key`,`station_no`,`task_role`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`food_line_no`,`id`),
  KEY `ix_food_ref_2` (`entity_no`,`component_no`,`id`),
  KEY `ix_food_ref_3` (`entity_no`,`station_no`,`id`),
  CONSTRAINT `fnb_kitchen_task_chk_1` CHECK ((`qty` > 0)),
  CONSTRAINT `fnb_kitchen_task_chk_2` CHECK ((`making_qty` >= 0)),
  CONSTRAINT `fnb_kitchen_task_chk_3` CHECK ((`ready_qty` >= 0)),
  CONSTRAINT `fnb_kitchen_task_chk_4` CHECK ((`served_qty` >= 0)),
  CONSTRAINT `fnb_kitchen_task_chk_5` CHECK ((`void_qty` >= 0)),
  CONSTRAINT `fnb_kitchen_task_chk_6` CHECK ((`served_qty` <= `ready_qty`)),
  CONSTRAINT `fnb_kitchen_task_chk_7` CHECK ((`ready_qty` <= `qty`)),
  CONSTRAINT `fnb_kitchen_task_chk_8` CHECK ((`making_qty` <= `qty`)),
  CONSTRAINT `fnb_kitchen_task_chk_9` CHECK ((`void_qty` <= `qty`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工位制作任务';

-- 四、ADR-068 修订二：占台、换台记录、订位、时段与容量随桌台归行业库（设计 DDL 里原拟进门店交易库）。
CREATE TABLE `fnb_table_occupancy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntity自增物理主键，不对外不进URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿用BaseEntity，一期MAIN；不是entity_no，不另启租户体系',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '当前主体或业务发生时主体快照，不等于账号和支付商户号',
  `created_at` datetime(3) NOT NULL COMMENT '沿用MetaObjectHandler及现有LocalDateTime持久化约定',
  `created_by` varchar(32) NOT NULL COMMENT '当前认证业务键；按现有填充规则，作业SYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT '沿用公共填充',
  `updated_by` varchar(32) NOT NULL COMMENT '沿用公共填充',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntity乐观锁',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntity逻辑删除；签核和事实表禁止业务归档覆盖历史',
  `occupancy_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '占用号',
  `table_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '桌号',
  `check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '台账号',
  `seats` int NOT NULL COMMENT '占座数',
  `started_at` datetime(3) NOT NULL COMMENT '开始',
  `ended_at` datetime(3) DEFAULT NULL COMMENT '结束',
  `resource_claim_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '资源仲裁号',
  `sharing_mode` varchar(16) NOT NULL DEFAULT 'EXCLUSIVE' COMMENT 'EXCLUSIVE / SHARED; occupancy arbitration mode, not a payment state',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`occupancy_no`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`table_no`,`id`),
  KEY `ix_food_ref_2` (`entity_no`,`check_no`,`id`),
  CONSTRAINT `ck_fnb_occupancy_sharing_mode` CHECK ((`sharing_mode` in (_latin1'EXCLUSIVE',_latin1'SHARED'))),
  CONSTRAINT `fnb_table_occupancy_chk_1` CHECK ((`seats` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='占桌关系';

CREATE TABLE `fnb_table_operation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntity自增物理主键，不对外不进URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿用BaseEntity，一期MAIN；不是entity_no，不另启租户体系',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '当前主体或业务发生时主体快照，不等于账号和支付商户号',
  `created_at` datetime(3) NOT NULL COMMENT '沿用MetaObjectHandler及现有LocalDateTime持久化约定',
  `created_by` varchar(32) NOT NULL COMMENT '当前认证业务键；按现有填充规则，作业SYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT '沿用公共填充',
  `updated_by` varchar(32) NOT NULL COMMENT '沿用公共填充',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntity乐观锁',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntity逻辑删除；签核和事实表禁止业务归档覆盖历史',
  `operation_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '可恢复操作号',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '门店',
  `kind` varchar(24) NOT NULL COMMENT 'TRANSFER / COMBINE_TABLES / SHARE_TABLE / MERGE_SERVICE / SPLIT_SERVICE / CLEAR',
  `check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '来源台账',
  `target_check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '目标台账',
  `request_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '幂等键',
  `request_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '参数哈希',
  `expected_versions` json NOT NULL COMMENT '受控台账/桌台版本集合',
  `target_scope` json NOT NULL COMMENT '桌台/人数/座位配置，不包含资金迁移',
  `resource_operation_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '资源仲裁操作引用',
  `status` varchar(24) NOT NULL COMMENT 'PREPARED / CLAIMING / APPLIED / RECOVERING / FAILED',
  `reason` varchar(255) NOT NULL COMMENT '原因',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`operation_no`),
  UNIQUE KEY `uk_food_2` (`store_no`,`kind`,`request_key`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_store` (`entity_no`,`store_no`,`updated_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`check_no`,`id`),
  KEY `ix_food_ref_2` (`entity_no`,`target_check_no`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='桌台协作操作意图 Table operation';

CREATE TABLE `fnb_reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntity自增物理主键，不对外不进URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿用BaseEntity，一期MAIN；不是entity_no，不另启租户体系',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '当前主体或业务发生时主体快照，不等于账号和支付商户号',
  `created_at` datetime(3) NOT NULL COMMENT '沿用MetaObjectHandler及现有LocalDateTime持久化约定',
  `created_by` varchar(32) NOT NULL COMMENT '当前认证业务键；按现有填充规则，作业SYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT '沿用公共填充',
  `updated_by` varchar(32) NOT NULL COMMENT '沿用公共填充',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntity乐观锁',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntity逻辑删除；签核和事实表禁止业务归档覆盖历史',
  `reservation_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '预约号',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '门店',
  `resource_hold_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '共享占位号',
  `buyer_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '真实顾客',
  `contact_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '加密联系方式引用',
  `party_size` int NOT NULL COMMENT '人数',
  `start_at` datetime(3) NOT NULL COMMENT '开始',
  `end_at` datetime(3) NOT NULL COMMENT '结束',
  `status` varchar(24) NOT NULL COMMENT 'HELD/CONFIRMED/ARRIVED/CANCELLED/NO_SHOW',
  `deposit_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '核心订金引用',
  `check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '转开台结果',
  `request_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `request_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`reservation_no`),
  UNIQUE KEY `uk_reservation_request` (`entity_no`,`store_no`,`request_key`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_store` (`entity_no`,`store_no`,`updated_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`check_no`,`id`),
  KEY `ix_reservation_window` (`entity_no`,`store_no`,`status`,`end_at`,`start_at`),
  CONSTRAINT `fnb_reservation_chk_1` CHECK ((`party_size` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约';

CREATE TABLE `fnb_reservation_table` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `created_by` varchar(32) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `updated_by` varchar(32) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `deleted` int NOT NULL DEFAULT '0',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `reservation_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `table_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_table` (`entity_no`,`reservation_no`,`table_no`,`deleted`),
  KEY `ix_reservation_table_conflict` (`entity_no`,`store_no`,`table_no`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='FOOD local bounded table reservation membership';

CREATE TABLE `fnb_service_slot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `created_by` varchar(32) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `updated_by` varchar(32) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `deleted` int NOT NULL DEFAULT '0',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `slot_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `starts_at` datetime(3) NOT NULL,
  `ends_at` datetime(3) NOT NULL,
  `order_cutoff_at` datetime(3) NOT NULL,
  `dispatch_at` datetime(3) NOT NULL,
  `capacity` decimal(18,6) NOT NULL,
  `reserved_qty` decimal(18,6) NOT NULL,
  `consumed_qty` decimal(18,6) NOT NULL,
  `unit` varchar(16) NOT NULL,
  `status` varchar(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_service_slot` (`slot_no`),
  KEY `ix_food_slot_schedule` (`entity_no`,`store_no`,`starts_at`),
  CONSTRAINT `fnb_service_slot_chk_1` CHECK (((`capacity` > 0) and (`reserved_qty` >= 0) and (`consumed_qty` >= 0) and ((`reserved_qty` + `consumed_qty`) <= `capacity`))),
  CONSTRAINT `fnb_service_slot_chk_2` CHECK (((`order_cutoff_at` <= `dispatch_at`) and (`dispatch_at` <= `starts_at`) and (`starts_at` < `ends_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `fnb_capacity_claim` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `created_by` varchar(32) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `updated_by` varchar(32) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `deleted` int NOT NULL DEFAULT '0',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `claim_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `slot_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `quantity` decimal(18,6) NOT NULL,
  `status` varchar(16) NOT NULL,
  `batch_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_capacity_claim` (`claim_no`),
  UNIQUE KEY `uk_food_capacity_source` (`entity_no`,`store_no`,`source_no`),
  UNIQUE KEY `uk_food_capacity_batch` (`entity_no`,`store_no`,`batch_no`),
  CONSTRAINT `fnb_capacity_claim_chk_1` CHECK (((`quantity` > 0) and (`status` in (_utf8mb4'RESERVED',_utf8mb4'CONSUMED',_utf8mb4'RELEASED'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `fnb_service_calendar` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntity自增物理主键，不对外不进URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿用BaseEntity，一期MAIN；不是entity_no，不另启租户体系',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '当前主体或业务发生时主体快照，不等于账号和支付商户号',
  `created_at` datetime(3) NOT NULL COMMENT '沿用MetaObjectHandler及现有LocalDateTime持久化约定',
  `created_by` varchar(32) NOT NULL COMMENT '当前认证业务键；按现有填充规则，作业SYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT '沿用公共填充',
  `updated_by` varchar(32) NOT NULL COMMENT '沿用公共填充',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntity乐观锁',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntity逻辑删除；签核和事实表禁止业务归档覆盖历史',
  `calendar_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '编号',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '门店',
  `local_date` date NOT NULL COMMENT '门店日期',
  `mode` varchar(16) NOT NULL COMMENT 'OPEN/CLOSED',
  `intervals` json NOT NULL COMMENT '受限营业时段数组',
  `policy_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '生效策略',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`calendar_no`),
  UNIQUE KEY `uk_food_2` (`store_no`,`local_date`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_store` (`entity_no`,`store_no`,`updated_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`policy_no`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='餐饮接单日历例外';

-- 五、过渡表已全部合并或改名完毕（TDD §2.5）。

-- 合并 ⑥ 服务事件 → 厨房事件（TDD §2.8 D-1）：厨房动作的事实只写这一张；动作防重键与来源请求摘要代码在用，补回并建唯一键。
-- 厨显推送流 fnb_kds_event 与游标在 D-3 改为「拉当前状态」后删除。
CREATE TABLE `fnb_kitchen_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '租户号',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属主体编号',
  `event_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '事件编号',
  `task_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '制作任务编号',
  `event_type` varchar(24) NOT NULL COMMENT '厨房动作：START 开始制作、READY 出品、SERVE 上菜、HOLD 叫停、FIRE 起菜、URGE 催菜、VOID 划菜、LOSS 损耗、HANDOVER 打包交接、MATERIAL_SUPPLEMENT 补料、MATERIAL_WASTE 废料',
  `qty` decimal(18,6) NOT NULL COMMENT '本次涉及数量',
  `ready_loss_qty` decimal(24,6) DEFAULT NULL COMMENT '损耗时其中已做好的数量；空为旧数据，整笔按成品损耗',
  `operation_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '动作防重键',
  `source_request_hash` varchar(64) DEFAULT NULL COMMENT '来源请求摘要，重放时比对同键异参',
  `reason` varchar(255) DEFAULT NULL COMMENT '原因（催菜、划菜、损耗）',
  `inventory_doc_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '损耗对应的库存出库凭据编号',
  `custody_snapshot` text COMMENT '打包交接时定格的原始行与接收范围',
  `version` bigint NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` int NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 正常，1 已删除',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，即动作发生时间',
  `created_by` varchar(32) NOT NULL COMMENT '操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  `updated_by` varchar(32) NOT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kitchen_event_no` (`event_no`),
  UNIQUE KEY `uk_kitchen_event_operation` (`operation_key`),
  KEY `ix_kitchen_event_task` (`entity_no`,`task_no`,`id`),
  KEY `ix_kitchen_event_audit` (`entity_no`,`created_at`,`id`),
  CONSTRAINT `fnb_kitchen_event_chk_qty` CHECK ((`qty` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='厨房事件：厨房动作的事实（开始、出品、上菜、叫停、催菜、划菜、损耗、交接），报表、耗料、日结、退货回库都读它';

-- 合并 ⑦ 交接与责任 → ind_assignment（TDD §2.5）：交接单号（多条指派归成一张交接）与来源摘要（接收时比对来源有没有变）代码在用。
ALTER TABLE ind_assignment ADD COLUMN handover_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '交接单号：同一次交接的各条指派', ADD COLUMN source_hash CHAR(64) NULL COMMENT '发起交接时对象的来源摘要', ADD KEY ix_ind_assignment_handover (entity_no, handover_no);
