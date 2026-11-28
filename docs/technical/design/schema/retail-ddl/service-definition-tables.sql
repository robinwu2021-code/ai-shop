-- 门店交易库 · 服务定义（用户 2026-11-28 定：服务 = 商品 + SKU + 服务定义；TDD-美业域迁移新库 §2.8、商品域-多业态商品方案 §3.5）。
-- 由 scripts/gen-retail-ddl.py 原样并进 05-n_trade.sql（接在 prd_fulfillment_requirement 之后），列与约束不经通用列规则改写。
-- 结构：按商品写一行默认值（sku_no=''），某个 SKU 与默认不同的再写一行该 SKU 的完整定义（时长、缓冲必填；步骤、注意事项为空表示沿用默认行）。
-- 版本：definition_version 按商品自增，一个版本是一组行（默认行加覆盖行，连同 prd_fulfillment_requirement.definition_version 相同的服务要求）；与商品修订号无关。

CREATE TABLE `prd_service_definition` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '租户号',
  `entity_no` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '所属经营主体',
  `store_no` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '门店编号，与服务商品的门店一致；空表示主体下各店共用的商品',
  `goods_no` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '服务商品编号',
  `sku_no` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'SKU 编号；空串表示商品默认行，非空表示这个 SKU 与默认不同的定义',
  `definition_version` int NOT NULL COMMENT '服务定义版本：同一商品内自增，与商品修订号无关；预约项目行、服务任务记的就是它',
  `duration_min` int NOT NULL COMMENT '服务时长（分钟）；默认行的时长镜像到 prd_goods.duration_min',
  `buffer_before_min` int NOT NULL DEFAULT 0 COMMENT '服务前缓冲（分钟）：占资源、不计服务时长',
  `buffer_after_min` int NOT NULL DEFAULT 0 COMMENT '服务后缓冲（分钟）：占资源、不计服务时长',
  `steps` json NULL COMMENT '服务步骤模板（JSON 数组）；覆盖行为空表示沿用默认行',
  `notes` json NULL COMMENT '注意事项（JSON 字符串数组）；覆盖行为空表示沿用默认行',
  `schema_version` int NOT NULL DEFAULT 1 COMMENT '步骤与注意事项的结构版本',
  `snapshot_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '本行内容摘要',
  `status` varchar(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 生效、INACTIVE 停用（以版本内默认行为准）',
  `source_ref` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '发布方的操作号（如美业的发布请求）；同一操作重放返回同一版本',
  `published_by` varchar(64) DEFAULT NULL COMMENT '发布人',
  `published_at` datetime(3) DEFAULT NULL COMMENT '发布时间（UTC）',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 = 正常，1 = 已删除',
  `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次修改加 1',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，每次修改自动更新',
  `updated_by` varchar(64) DEFAULT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prd_service_definition_version` (`tenant_no`, `goods_no`, `definition_version`, `sku_no`),
  UNIQUE KEY `uk_prd_service_definition_source` (`tenant_no`, `goods_no`, `source_ref`, `sku_no`),
  KEY `idx_prd_service_definition_store` (`tenant_no`, `entity_no`, `store_no`, `goods_no`),
  CONSTRAINT `ck_prd_service_definition_duration` CHECK (`duration_min` > 0),
  CONSTRAINT `ck_prd_service_definition_buffer` CHECK (`buffer_before_min` >= 0 AND `buffer_after_min` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='服务定义：服务做这一档要多久、前后缓冲、步骤、注意事项；商品默认行加按 SKU 的覆盖行，有自己的版本号，与商品修订脱钩';
