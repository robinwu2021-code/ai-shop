-- 送货方式挂门店：每店每路一行（方案 v4 §四/§五）。
-- 取代 mch_entity.fulfillment_reach 单选三档 —— 列本版保留只读，退役走三步（播种→读切换→删列）。

CREATE TABLE mch_fulfillment_channel (
  id BIGINT NOT NULL AUTO_INCREMENT,
  store_no VARCHAR(64) NOT NULL COMMENT '挂门店：开哪几路是网点的物理能力。空 store 的读侧走「默认门店」兜底，与订单履约同一口径',
  entity_no VARCHAR(64) NOT NULL COMMENT '冗余自门店：鉴权与主体级汇总不用连表',
  channel VARCHAR(24) NOT NULL COMMENT 'Fulfillments 值域的商家可配子集：STORE_PICKUP/NEIGHBOR_PICKUP/MERCHANT_DELIVERY/EXPRESS。STORE_VERIFY/APPOINTMENT 是服务类商品属性，写入即拒',
  enabled TINYINT NOT NULL DEFAULT 0 COMMENT '行存在但 enabled=0 不等于无行：关一路时配置原地保留，再打开原样回来',
  scope_mode VARCHAR(8) NOT NULL DEFAULT 'ALL' COMMENT 'ALL 继承整个经营范围 / SUBSET 收窄到 mch_channel_area（P2）。EXPRESS 恒为 ALL',
  config TEXT NULL COMMENT 'JSON，按 channel 各取所需：EXPRESS 存 {"templateNo":"…"}。自送费率不在这里 —— 费率跟门店走，见 mch_store.delivery_*',
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '恒为 0 —— 本表走物理删除，这一列只为与 BaseEntity 对齐',
  PRIMARY KEY (id),
  UNIQUE KEY uk_store_channel (tenant_no, store_no, channel),
  KEY idx_channel_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店送货方式：每店每路一行的开关与配置';

CREATE TABLE mch_channel_pickup (
  id BIGINT NOT NULL AUTO_INCREMENT,
  store_no VARCHAR(64) NOT NULL,
  channel VARCHAR(24) NOT NULL COMMENT '只允许 STORE_PICKUP / NEIGHBOR_PICKUP',
  pickup_no VARCHAR(64) NOT NULL COMMENT 'cmt_pickup_point.pickup_no。点被停用后不删本行，读侧过滤 —— 点恢复后配置原样回来',
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '恒为 0 —— 本表走物理删除，这一列只为与 BaseEntity 对齐',
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_pickup (tenant_no, store_no, channel, pickup_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自提路×取货点（P1 启用）。本店地址刻意不落行：门店地址天然是取货地址，存两份是漂移的起点';

CREATE TABLE mch_channel_area (
  id BIGINT NOT NULL AUTO_INCREMENT,
  store_no VARCHAR(64) NOT NULL,
  channel VARCHAR(24) NOT NULL COMMENT 'EXPRESS 不允许出现（快递天然全国，超区规则在运费模板里）',
  area_no VARCHAR(64) NOT NULL COMMENT 'mch_service_area.area_no。范围项仍是主体级 —— 门店在主体申报的大范围里各自收窄',
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '恒为 0 —— 本表走物理删除，这一列只为与 BaseEntity 对齐',
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_area (tenant_no, store_no, channel, area_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SUBSET 收窄：某店某路只适用哪些范围项（P2 启用）';

-- 播种：按主体旧单值给该主体的每家门店各播一套行，行为与今天完全一致。
-- 幂等判据按 (store, channel) —— 同一版里多条 INSERT 依次执行，按「店无行」判会让后面的语句
-- 误以为已播过；按表空判则是 V181 踩过的坑。

-- PICKUP → 社区自提点自取
INSERT INTO mch_fulfillment_channel
  (store_no, entity_no, channel, enabled, scope_mode, tenant_no, created_at, updated_at)
SELECT s.store_no, s.entity_no, 'NEIGHBOR_PICKUP', 1, 'ALL', s.tenant_no, NOW(), NOW()
FROM mch_store s
JOIN mch_entity e ON e.entity_no = s.entity_no AND e.deleted = 0
WHERE s.deleted = 0
  AND e.fulfillment_reach = 'PICKUP'
  AND NOT EXISTS (SELECT 1 FROM mch_fulfillment_channel c
                  WHERE c.store_no = s.store_no AND c.channel = 'NEIGHBOR_PICKUP');

-- PICKUP 且门店有地址 → 门店自取也开（地址即取货地址）
INSERT INTO mch_fulfillment_channel
  (store_no, entity_no, channel, enabled, scope_mode, tenant_no, created_at, updated_at)
SELECT s.store_no, s.entity_no, 'STORE_PICKUP', 1, 'ALL', s.tenant_no, NOW(), NOW()
FROM mch_store s
JOIN mch_entity e ON e.entity_no = s.entity_no AND e.deleted = 0
WHERE s.deleted = 0
  AND e.fulfillment_reach = 'PICKUP'
  AND s.address IS NOT NULL AND s.address <> ''
  AND NOT EXISTS (SELECT 1 FROM mch_fulfillment_channel c
                  WHERE c.store_no = s.store_no AND c.channel = 'STORE_PICKUP');

-- ONSITE → 商家自送（费率沿用 mch_store.delivery_* 现值，不搬家）
INSERT INTO mch_fulfillment_channel
  (store_no, entity_no, channel, enabled, scope_mode, tenant_no, created_at, updated_at)
SELECT s.store_no, s.entity_no, 'MERCHANT_DELIVERY', 1, 'ALL', s.tenant_no, NOW(), NOW()
FROM mch_store s
JOIN mch_entity e ON e.entity_no = s.entity_no AND e.deleted = 0
WHERE s.deleted = 0
  AND e.fulfillment_reach = 'ONSITE'
  AND NOT EXISTS (SELECT 1 FROM mch_fulfillment_channel c
                  WHERE c.store_no = s.store_no AND c.channel = 'MERCHANT_DELIVERY');

-- SHIPPING → 快递，运费模板给平台默认模板（默认模板不能归档的既有约束兜底）
INSERT INTO mch_fulfillment_channel
  (store_no, entity_no, channel, enabled, scope_mode, config, tenant_no, created_at, updated_at)
SELECT s.store_no, s.entity_no, 'EXPRESS', 1, 'ALL',
       (SELECT CONCAT('{"templateNo":"', t.template_no, '"}')
        FROM ful_freight_template t
        WHERE t.is_default = 1 AND t.deleted = 0 LIMIT 1),
       s.tenant_no, NOW(), NOW()
FROM mch_store s
JOIN mch_entity e ON e.entity_no = s.entity_no AND e.deleted = 0
WHERE s.deleted = 0
  AND e.fulfillment_reach = 'SHIPPING'
  AND NOT EXISTS (SELECT 1 FROM mch_fulfillment_channel c
                  WHERE c.store_no = s.store_no AND c.channel = 'EXPRESS');
