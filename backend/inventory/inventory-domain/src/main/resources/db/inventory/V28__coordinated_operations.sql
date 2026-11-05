-- 协同库存操作（商城硬件 SKU）：只存操作身份与栅栏，数量与流水仍在原有的预留 / 余额 / 流水账里。
--
-- 原实现在 codex/commerce-foundation 的 V14（统一线 V14 已是 exact_demand_plan），这里接 V27 之后。
-- 这两张表挂的 inv_reservation 行用生命周期 4（COORDINATED）：统一线 1/2/3 已分别是
-- 付款扣减 / 交付扣减 / 先货后款，原实现借用的 3 在这里另有含义。生命周期 4 的预留不到期回收，
-- 旧的按单号 / 履约单号的动作一律拒绝，只能经协同协议释放或发货。
CREATE TABLE inv_coordinated_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    client_code VARCHAR(32) NOT NULL COMMENT '协调方（如 PLATFORM_MALL）',
    external_ref VARCHAR(64) NOT NULL COMMENT '协调方单号（如商城组件号）',
    reserve_operation_no VARCHAR(64) NOT NULL COMMENT '第一个预留 / 释放操作号；之后只认它',
    owner_id VARCHAR(32) DEFAULT NULL COMMENT '首次预留时冻结的业主；NULL = 先到的是释放（栅栏）',
    item_id VARCHAR(32) DEFAULT NULL,
    location_id VARCHAR(32) DEFAULT NULL,
    mapping_version VARCHAR(64) DEFAULT NULL COMMENT '协调方映射版本，冻结',
    request_reference VARCHAR(64) DEFAULT NULL,
    original_quantity INT DEFAULT NULL,
    reservation_id VARCHAR(32) DEFAULT NULL COMMENT 'inv_reservation.reservation_id（lifecycle_version=4）',
    released TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 已释放（栅栏），之后不再预留、不再发货',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_coordinated_ref (client_code, external_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='协同预留：操作身份与释放栅栏';

CREATE TABLE inv_coordinated_shipment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    client_code VARCHAR(32) NOT NULL,
    external_ref VARCHAR(64) NOT NULL,
    operation_no VARCHAR(64) NOT NULL COMMENT '发货操作号，按它幂等',
    quantity INT NOT NULL,
    carrier VARCHAR(64) NOT NULL,
    tracking_no VARCHAR(128) NOT NULL,
    operator_no VARCHAR(64) NOT NULL,
    outbound_no VARCHAR(32) NOT NULL COMMENT '生成的销售出库单号',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_coordinated_ship (client_code, external_ref, operation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='协同发货：每个发货操作一行，证明出库单';
