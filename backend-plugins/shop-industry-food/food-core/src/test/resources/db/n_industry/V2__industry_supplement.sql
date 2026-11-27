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
    DROP INDEX uk_food_1,
    ADD UNIQUE KEY uk_food_1 (station_no);

ALTER TABLE fnb_kds_event
    MODIFY entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY sequence_no BIGINT NOT NULL,
    MODIFY event_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    MODIFY event_type VARCHAR(40) NOT NULL,
    MODIFY source_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY source_version BIGINT NOT NULL,
    MODIFY payload MEDIUMTEXT NOT NULL,
    MODIFY occurred_at DATETIME(3) NOT NULL,
    DROP INDEX uk_kds_source,
    ADD UNIQUE KEY uk_kds_source (entity_no, store_no, event_no),
    ADD UNIQUE KEY uk_kds_sequence (entity_no, store_no, sequence_no);

ALTER TABLE fnb_kds_subscription
    MODIFY subscription_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY device_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY actor_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY status VARCHAR(16) NOT NULL,
    MODIFY version BIGINT NOT NULL DEFAULT 0,
    MODIFY requested_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY revoked_by VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD COLUMN request_hash VARCHAR(64) NOT NULL,
    ADD COLUMN revoke_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_kds_subscription (subscription_no),
    ADD UNIQUE KEY uk_kds_grant (entity_no, store_no, requested_by, request_key);

CREATE TABLE fnb_kds_cursor (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 sequence_no BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(entity_no,store_no)
) ENGINE=InnoDB;

CREATE TABLE fnb_station_runtime (
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 station_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 status VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL,
 PRIMARY KEY(entity_no,store_no,station_no)
) ENGINE=InnoDB;

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
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntityè‡ªå¢žç‰©ç†ä¸»é”®ï¼Œä¸å¯¹å¤–ä¸è¿›URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT 'æ²¿ç”¨BaseEntityï¼Œä¸€æœŸMAINï¼›ä¸æ˜¯entity_noï¼Œä¸å¦å¯ç§Ÿæˆ·ä½“ç³»',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å½“å‰ä¸»ä½“æˆ–ä¸šåŠ¡å‘ç”Ÿæ—¶ä¸»ä½“å¿«ç…§ï¼Œä¸ç­‰äºŽè´¦å·å’Œæ”¯ä»˜å•†æˆ·å·',
  `created_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨MetaObjectHandleråŠçŽ°æœ‰LocalDateTimeæŒä¹…åŒ–çº¦å®š',
  `created_by` varchar(32) NOT NULL COMMENT 'å½“å‰è®¤è¯ä¸šåŠ¡é”®ï¼›æŒ‰çŽ°æœ‰å¡«å……è§„åˆ™ï¼Œä½œä¸šSYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `updated_by` varchar(32) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntityä¹è§‚é”',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntityé€»è¾‘åˆ é™¤ï¼›ç­¾æ ¸å’Œäº‹å®žè¡¨ç¦æ­¢ä¸šåŠ¡å½’æ¡£è¦†ç›–åŽ†å²',
  `task_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ä»»åŠ¡å·',
  `ticket_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ä¸‹è¾¾å•',
  `food_line_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'èœå“è¡Œ',
  `component_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'å¥—é¤ç»„ä»¶',
  `station_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å·¥ä½',
  `route_version` bigint NOT NULL COMMENT 'è·¯ç”±ç‰ˆæœ¬',
  `qty` decimal(18,6) NOT NULL COMMENT 'ç›®æ ‡é‡',
  `making_qty` decimal(18,6) NOT NULL COMMENT 'åˆ¶ä½œé‡',
  `ready_qty` decimal(18,6) NOT NULL COMMENT 'å‡ºå“é‡',
  `served_qty` decimal(18,6) NOT NULL COMMENT 'ä¸Šèœé‡',
  `void_qty` decimal(18,6) NOT NULL COMMENT 'æ’¤é”€é‡',
  `status` varchar(16) NOT NULL COMMENT 'HELD/QUEUED/MAKING/READY/SERVED/VOIDED',
  `task_part_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'éžç©ºæºè¡Œ/ç»„ä»¶ç¨³å®šé”®',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='å·¥ä½åˆ¶ä½œä»»åŠ¡';

-- 四、ADR-068 修订二：占台、换台记录、订位、时段与容量随桌台归行业库（设计 DDL 里原拟进门店交易库）。
CREATE TABLE `fnb_table_occupancy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntityè‡ªå¢žç‰©ç†ä¸»é”®ï¼Œä¸å¯¹å¤–ä¸è¿›URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT 'æ²¿ç”¨BaseEntityï¼Œä¸€æœŸMAINï¼›ä¸æ˜¯entity_noï¼Œä¸å¦å¯ç§Ÿæˆ·ä½“ç³»',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å½“å‰ä¸»ä½“æˆ–ä¸šåŠ¡å‘ç”Ÿæ—¶ä¸»ä½“å¿«ç…§ï¼Œä¸ç­‰äºŽè´¦å·å’Œæ”¯ä»˜å•†æˆ·å·',
  `created_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨MetaObjectHandleråŠçŽ°æœ‰LocalDateTimeæŒä¹…åŒ–çº¦å®š',
  `created_by` varchar(32) NOT NULL COMMENT 'å½“å‰è®¤è¯ä¸šåŠ¡é”®ï¼›æŒ‰çŽ°æœ‰å¡«å……è§„åˆ™ï¼Œä½œä¸šSYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `updated_by` varchar(32) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntityä¹è§‚é”',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntityé€»è¾‘åˆ é™¤ï¼›ç­¾æ ¸å’Œäº‹å®žè¡¨ç¦æ­¢ä¸šåŠ¡å½’æ¡£è¦†ç›–åŽ†å²',
  `occupancy_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å ç”¨å·',
  `table_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'æ¡Œå·',
  `check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å°è´¦å·',
  `seats` int NOT NULL COMMENT 'å åº§æ•°',
  `started_at` datetime(3) NOT NULL COMMENT 'å¼€å§‹',
  `ended_at` datetime(3) DEFAULT NULL COMMENT 'ç»“æŸ',
  `resource_claim_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'èµ„æºä»²è£å·',
  `sharing_mode` varchar(16) NOT NULL DEFAULT 'EXCLUSIVE' COMMENT 'EXCLUSIVE / SHARED; occupancy arbitration mode, not a payment state',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`occupancy_no`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`table_no`,`id`),
  KEY `ix_food_ref_2` (`entity_no`,`check_no`,`id`),
  CONSTRAINT `ck_fnb_occupancy_sharing_mode` CHECK ((`sharing_mode` in (_latin1'EXCLUSIVE',_latin1'SHARED'))),
  CONSTRAINT `fnb_table_occupancy_chk_1` CHECK ((`seats` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='å æ¡Œå…³ç³»';

CREATE TABLE `fnb_table_operation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntityè‡ªå¢žç‰©ç†ä¸»é”®ï¼Œä¸å¯¹å¤–ä¸è¿›URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT 'æ²¿ç”¨BaseEntityï¼Œä¸€æœŸMAINï¼›ä¸æ˜¯entity_noï¼Œä¸å¦å¯ç§Ÿæˆ·ä½“ç³»',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å½“å‰ä¸»ä½“æˆ–ä¸šåŠ¡å‘ç”Ÿæ—¶ä¸»ä½“å¿«ç…§ï¼Œä¸ç­‰äºŽè´¦å·å’Œæ”¯ä»˜å•†æˆ·å·',
  `created_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨MetaObjectHandleråŠçŽ°æœ‰LocalDateTimeæŒä¹…åŒ–çº¦å®š',
  `created_by` varchar(32) NOT NULL COMMENT 'å½“å‰è®¤è¯ä¸šåŠ¡é”®ï¼›æŒ‰çŽ°æœ‰å¡«å……è§„åˆ™ï¼Œä½œä¸šSYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `updated_by` varchar(32) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntityä¹è§‚é”',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntityé€»è¾‘åˆ é™¤ï¼›ç­¾æ ¸å’Œäº‹å®žè¡¨ç¦æ­¢ä¸šåŠ¡å½’æ¡£è¦†ç›–åŽ†å²',
  `operation_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å¯æ¢å¤æ“ä½œå·',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'é—¨åº—',
  `kind` varchar(24) NOT NULL COMMENT 'TRANSFER / COMBINE_TABLES / SHARE_TABLE / MERGE_SERVICE / SPLIT_SERVICE / CLEAR',
  `check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'æ¥æºå°è´¦',
  `target_check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'ç›®æ ‡å°è´¦',
  `request_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å¹‚ç­‰é”®',
  `request_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å‚æ•°å“ˆå¸Œ',
  `expected_versions` json NOT NULL COMMENT 'å—æŽ§å°è´¦/æ¡Œå°ç‰ˆæœ¬é›†åˆ',
  `target_scope` json NOT NULL COMMENT 'æ¡Œå°/äººæ•°/åº§ä½é…ç½®ï¼Œä¸åŒ…å«èµ„é‡‘è¿ç§»',
  `resource_operation_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'èµ„æºä»²è£æ“ä½œå¼•ç”¨',
  `status` varchar(24) NOT NULL COMMENT 'PREPARED / CLAIMING / APPLIED / RECOVERING / FAILED',
  `reason` varchar(255) NOT NULL COMMENT 'åŽŸå› ',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`operation_no`),
  UNIQUE KEY `uk_food_2` (`store_no`,`kind`,`request_key`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_store` (`entity_no`,`store_no`,`updated_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`check_no`,`id`),
  KEY `ix_food_ref_2` (`entity_no`,`target_check_no`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='æ¡Œå°åä½œæ“ä½œæ„å›¾ Table operation';

CREATE TABLE `fnb_reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntityè‡ªå¢žç‰©ç†ä¸»é”®ï¼Œä¸å¯¹å¤–ä¸è¿›URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT 'æ²¿ç”¨BaseEntityï¼Œä¸€æœŸMAINï¼›ä¸æ˜¯entity_noï¼Œä¸å¦å¯ç§Ÿæˆ·ä½“ç³»',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å½“å‰ä¸»ä½“æˆ–ä¸šåŠ¡å‘ç”Ÿæ—¶ä¸»ä½“å¿«ç…§ï¼Œä¸ç­‰äºŽè´¦å·å’Œæ”¯ä»˜å•†æˆ·å·',
  `created_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨MetaObjectHandleråŠçŽ°æœ‰LocalDateTimeæŒä¹…åŒ–çº¦å®š',
  `created_by` varchar(32) NOT NULL COMMENT 'å½“å‰è®¤è¯ä¸šåŠ¡é”®ï¼›æŒ‰çŽ°æœ‰å¡«å……è§„åˆ™ï¼Œä½œä¸šSYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `updated_by` varchar(32) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntityä¹è§‚é”',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntityé€»è¾‘åˆ é™¤ï¼›ç­¾æ ¸å’Œäº‹å®žè¡¨ç¦æ­¢ä¸šåŠ¡å½’æ¡£è¦†ç›–åŽ†å²',
  `reservation_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'é¢„çº¦å·',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'é—¨åº—',
  `resource_hold_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'å…±äº«å ä½å·',
  `buyer_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'çœŸå®žé¡¾å®¢',
  `contact_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'åŠ å¯†è”ç³»æ–¹å¼å¼•ç”¨',
  `party_size` int NOT NULL COMMENT 'äººæ•°',
  `start_at` datetime(3) NOT NULL COMMENT 'å¼€å§‹',
  `end_at` datetime(3) NOT NULL COMMENT 'ç»“æŸ',
  `status` varchar(24) NOT NULL COMMENT 'HELD/CONFIRMED/ARRIVED/CANCELLED/NO_SHOW',
  `deposit_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'æ ¸å¿ƒè®¢é‡‘å¼•ç”¨',
  `check_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'è½¬å¼€å°ç»“æžœ',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='é¢„çº¦';

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
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntityè‡ªå¢žç‰©ç†ä¸»é”®ï¼Œä¸å¯¹å¤–ä¸è¿›URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT 'æ²¿ç”¨BaseEntityï¼Œä¸€æœŸMAINï¼›ä¸æ˜¯entity_noï¼Œä¸å¦å¯ç§Ÿæˆ·ä½“ç³»',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å½“å‰ä¸»ä½“æˆ–ä¸šåŠ¡å‘ç”Ÿæ—¶ä¸»ä½“å¿«ç…§ï¼Œä¸ç­‰äºŽè´¦å·å’Œæ”¯ä»˜å•†æˆ·å·',
  `created_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨MetaObjectHandleråŠçŽ°æœ‰LocalDateTimeæŒä¹…åŒ–çº¦å®š',
  `created_by` varchar(32) NOT NULL COMMENT 'å½“å‰è®¤è¯ä¸šåŠ¡é”®ï¼›æŒ‰çŽ°æœ‰å¡«å……è§„åˆ™ï¼Œä½œä¸šSYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `updated_by` varchar(32) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntityä¹è§‚é”',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntityé€»è¾‘åˆ é™¤ï¼›ç­¾æ ¸å’Œäº‹å®žè¡¨ç¦æ­¢ä¸šåŠ¡å½’æ¡£è¦†ç›–åŽ†å²',
  `calendar_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ç¼–å·',
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'é—¨åº—',
  `local_date` date NOT NULL COMMENT 'é—¨åº—æ—¥æœŸ',
  `mode` varchar(16) NOT NULL COMMENT 'OPEN/CLOSED',
  `intervals` json NOT NULL COMMENT 'å—é™è¥ä¸šæ—¶æ®µæ•°ç»„',
  `policy_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ç”Ÿæ•ˆç­–ç•¥',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`calendar_no`),
  UNIQUE KEY `uk_food_2` (`store_no`,`local_date`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_store` (`entity_no`,`store_no`,`updated_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`policy_no`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='é¤é¥®æŽ¥å•æ—¥åŽ†ä¾‹å¤–';

-- 五、过渡：9 组合并（TDD §2.5）与寄存改名完成前，代码仍读写的旧表；每完成一组就从这里删掉对应的表。

CREATE TABLE `fnb_service_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'BaseEntityè‡ªå¢žç‰©ç†ä¸»é”®ï¼Œä¸å¯¹å¤–ä¸è¿›URL',
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT 'æ²¿ç”¨BaseEntityï¼Œä¸€æœŸMAINï¼›ä¸æ˜¯entity_noï¼Œä¸å¦å¯ç§Ÿæˆ·ä½“ç³»',
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å½“å‰ä¸»ä½“æˆ–ä¸šåŠ¡å‘ç”Ÿæ—¶ä¸»ä½“å¿«ç…§ï¼Œä¸ç­‰äºŽè´¦å·å’Œæ”¯ä»˜å•†æˆ·å·',
  `created_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨MetaObjectHandleråŠçŽ°æœ‰LocalDateTimeæŒä¹…åŒ–çº¦å®š',
  `created_by` varchar(32) NOT NULL COMMENT 'å½“å‰è®¤è¯ä¸šåŠ¡é”®ï¼›æŒ‰çŽ°æœ‰å¡«å……è§„åˆ™ï¼Œä½œä¸šSYSTEM',
  `updated_at` datetime(3) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `updated_by` varchar(32) NOT NULL COMMENT 'æ²¿ç”¨å…¬å…±å¡«å……',
  `version` bigint NOT NULL DEFAULT '0' COMMENT 'BaseEntityä¹è§‚é”',
  `deleted` int NOT NULL DEFAULT '0' COMMENT 'BaseEntityé€»è¾‘åˆ é™¤ï¼›ç­¾æ ¸å’Œäº‹å®žè¡¨ç¦æ­¢ä¸šåŠ¡å½’æ¡£è¦†ç›–åŽ†å²',
  `event_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'äº‹ä»¶å·',
  `task_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ä»»åŠ¡',
  `action` varchar(24) NOT NULL COMMENT 'START/READY/SERVE/HOLD/FIRE/URGE/VOID/LOSS',
  `qty` decimal(18,6) NOT NULL COMMENT 'æœ¬æ¬¡æ•°é‡',
  `operation_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'å¹‚ç­‰æ“ä½œ',
  `reason` varchar(255) DEFAULT NULL COMMENT 'åŽŸå› ',
  `inventory_doc_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'æ ¸å¿ƒåº“å­˜å‡­æ®',
  `custody_snapshot` text COMMENT 'Immutable packing/custody receipt with original line and recipient scope',
  `source_request_hash` varchar(64) DEFAULT NULL,
  `ready_loss_qty` decimal(24,6) DEFAULT NULL COMMENT 'Null legacy LOSS was entirely ready food; new events freeze exact stage quantity',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_1` (`event_no`),
  UNIQUE KEY `uk_food_2` (`operation_key`),
  KEY `ix_food_audit` (`entity_no`,`created_at`,`id`),
  KEY `ix_food_ref_1` (`entity_no`,`task_no`,`id`),
  CONSTRAINT `fnb_service_event_chk_1` CHECK ((`qty` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='å‡ºå“ä¸ŠèœåŠæŸè€—äº‹å®ž';

-- 合并 ⑦ 交接与责任 → ind_assignment（TDD §2.5）：交接单号（多条指派归成一张交接）与来源摘要（接收时比对来源有没有变）代码在用。
ALTER TABLE ind_assignment ADD COLUMN handover_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '交接单号：同一次交接的各条指派', ADD COLUMN source_hash CHAR(64) NULL COMMENT '发起交接时对象的来源摘要', ADD KEY ix_ind_assignment_handover (entity_no, handover_no);
