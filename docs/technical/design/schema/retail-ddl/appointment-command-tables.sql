-- 门店交易库 · 预约的资源命令（用户 2026-11-28 定方向 A：TDD-美业域迁移新库 §2.6 第 4–6 项）。
-- 由 scripts/gen-retail-ddl.py 原样并进 05-n_trade.sql（接在 ord_appointment_revision 之后），列与约束不经通用列规则改写。
-- 一次预约变更（一行修订）可能发几条资源命令：新约先占位（RESERVE）再确认（CONFIRM），改约是替换（REPLACE），
-- 取消、爽约与确认失败后的补偿是释放（RELEASE）。宿主对每条命令单独问许可，同一命令重放要答回原来那次——所以一条命令一行。
-- 合并了美业原来的资源意向、资源决策，以及预约操作里的受理载荷与回执绑定；许可一经写入不再改。

CREATE TABLE `ord_appointment_command` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '租户号',
  `entity_no` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '所属经营主体',
  `store_no` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '门店编号',
  `appointment_no` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '预约单号',
  `change_no` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '所属的那次变更（ord_appointment_revision.change_no）',
  `request_key` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '发给宿主的命令键：变更号 + 动作；同一变更同一动作只有一条',
  `action` varchar(16) NOT NULL COMMENT '命令动作：RESERVE 占位、CONFIRM 确认、REPLACE 替换、RELEASE 释放',
  `source_revision` bigint NOT NULL COMMENT '发命令时预约单的版本（ord_appointment.version）',
  `accept_payload` json NOT NULL COMMENT '受理请求原文：目标占位与版本、宿主侧期望版本、冻结的资源计划',
  `command_no` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '宿主受理后给的命令号；宿主问许可、对账按它找这一行',
  `command_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '宿主绑定的命令摘要',
  `plan_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '资源计划摘要',
  `receipt` json NULL COMMENT '宿主受理回执（绑定与结果）；恢复、对账按它核对',
  `status` varchar(10) NOT NULL COMMENT '状态：SENT 已发待绑定、BOUND 已绑定待许可、PERMITTED 已许可、REFUSED 许可前拒绝、SUCCEEDED 宿主成功、REJECTED 宿主拒绝',
  `decision_no` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '许可或拒绝的决策号；一经写入不再改',
  `permitted_at` datetime(3) DEFAULT NULL COMMENT '许可时刻',
  `valid_until` datetime(3) DEFAULT NULL COMMENT '许可有效截止；重放时原样答回，过期也一样',
  `fence_no` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '答给宿主的来源闸门号（预约单号）',
  `fence_revision` bigint DEFAULT NULL COMMENT '答给宿主的闸门版本（这次变更的修订号）',
  `reject_code` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '许可前拒绝或宿主拒绝的原因码',
  `result_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '宿主终态回执摘要；有值即已对账，不再改',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 = 正常，1 = 已删除',
  `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次修改加 1',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，每次修改自动更新',
  `updated_by` varchar(64) DEFAULT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_appointment_command_key` (`tenant_no`, `entity_no`, `request_key`),
  UNIQUE KEY `uk_ord_appointment_command_no` (`tenant_no`, `entity_no`, `command_no`),
  KEY `idx_ord_appointment_command_change` (`tenant_no`, `entity_no`, `change_no`),
  CONSTRAINT `ck_ord_appointment_command_decision` CHECK (((`status` in ('SENT','BOUND')) and (`decision_no` is null))
    or ((`status` in ('PERMITTED','REFUSED','SUCCEEDED','REJECTED')) and (`decision_no` is not null))
    or ((`status` = 'REJECTED') and (`decision_no` is null))),
  CONSTRAINT `ck_ord_appointment_command_permit` CHECK ((`permitted_at` is null) or (`valid_until` > `permitted_at`)),
  CONSTRAINT `ck_ord_appointment_command_window` CHECK (((`status` in ('SENT','BOUND','REFUSED')) and (`permitted_at` is null))
    or ((`status` in ('PERMITTED','SUCCEEDED')) and (`permitted_at` is not null)) or (`status` = 'REJECTED')),
  CONSTRAINT `ck_ord_appointment_command_revision` CHECK (`source_revision` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约的资源命令：一次预约变更发给资源方的每条命令一行，记受理请求、回执、许可与终态；许可一经写入不再改';
