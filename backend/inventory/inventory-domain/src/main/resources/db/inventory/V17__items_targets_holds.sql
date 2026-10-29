-- 进销存独立服务 · 物料 / 库位 / 外部目标 / 余额冻结（TDD-进销存独立服务与多业态 §2.4 / §2.6 / §2.7，M2 T2.4 ~ T2.5）

-- 物料：跟踪方式、放行策略、宿主物料号、展示属性。存量全是商城的数量物料、无需放行
ALTER TABLE inv_item ADD COLUMN tracking_mode VARCHAR(16) NOT NULL DEFAULT 'QUANTITY' COMMENT 'QUANTITY / SERIAL（BATCH 保留不实现）；有流水后不可改';
ALTER TABLE inv_item ADD COLUMN gate_policy VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE / REF_REQUIRED：入库是否必须带宿主放行凭证';
ALTER TABLE inv_item ADD COLUMN gate_ref_type VARCHAR(32) DEFAULT NULL COMMENT '放行凭证类型，取自业态档案 gateRefTypes';
ALTER TABLE inv_item ADD COLUMN external_ref VARCHAR(64) DEFAULT NULL COMMENT '宿主物料号（宿主按它推送物料）';
ALTER TABLE inv_item ADD COLUMN attributes TEXT DEFAULT NULL COMMENT '宿主自管的展示属性 JSON，不参与任何规则';
CREATE UNIQUE INDEX uk_item_external_ref ON inv_item (owner_id, external_ref);

-- 库位：所属区域、停用时间（停用守卫：有余额 / 未完成单 / 冻结时逐项拒绝）
ALTER TABLE inv_location ADD COLUMN region_ref VARCHAR(64) DEFAULT NULL COMMENT '宿主的区域引用';
ALTER TABLE inv_location ADD COLUMN disabled_at DATETIME DEFAULT NULL COMMENT '停用时间；与 status=DISABLED 同时写';

-- 余额：不可用数量 = 有效冻结折算后的件数（同一件至多计 1）。available = on_hand - reserved - unavailable
ALTER TABLE inv_stock_balance ADD COLUMN unavailable_quantity DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '冻结（盘点 / 退货待确认 / 人工）占用的数量';

-- 多原因冻结：可并存、按原因单独解除。SERIAL 物料逐件（serial_id），数量物料按数量
CREATE TABLE inv_stock_hold (
    id BIGINT NOT NULL AUTO_INCREMENT,
    hold_id VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    location_id VARCHAR(32) NOT NULL,
    serial_id VARCHAR(32) DEFAULT NULL COMMENT 'SERIAL 物料的单件；数量物料为空',
    qty DECIMAL(24,6) NOT NULL COMMENT '数量物料的冻结数量；单件恒为 1',
    reason VARCHAR(24) NOT NULL COMMENT 'COUNT / RETURN_PENDING / MANUAL',
    source_ref VARCHAR(64) DEFAULT NULL COMMENT '盘点单号、退货入库单号等',
    note VARCHAR(255) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / RELEASED。解除不删行',
    released_at DATETIME DEFAULT NULL,
    released_by VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_stock_hold (owner_id, hold_id),
    KEY idx_inv_stock_hold_balance (owner_id, item_id, location_id, status),
    KEY idx_inv_stock_hold_source (owner_id, reason, source_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多原因冻结（解除不删行）';

-- 外部目标：宿主单向同步来的收货方 / 接收人。类型由业态档案注册，不是枚举
CREATE TABLE inv_target_ref (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    system_code VARCHAR(32) NOT NULL,
    object_type VARCHAR(32) NOT NULL COMMENT '档案 targetTypes 里的类型码，如 MERCHANT / SITE / PERSON',
    object_ref VARCHAR(64) NOT NULL COMMENT '宿主侧的编号',
    name VARCHAR(128) NOT NULL,
    parent_type VARCHAR(32) DEFAULT NULL,
    parent_ref VARCHAR(64) DEFAULT NULL COMMENT '上级目标编号，如站点所属商家',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED：停用后不能再作新发货的收货方',
    source_version BIGINT NOT NULL DEFAULT 0 COMMENT '宿主侧版本；旧版本到达忽略',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_target_ref (owner_id, system_code, object_type, object_ref),
    KEY idx_inv_target_ref_parent (owner_id, parent_type, parent_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部目标（宿主同步的收货方 / 接收人）';
