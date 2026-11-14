-- 进销存独立服务 · 组织边界与成对单据（TDD-进销存独立服务与多业态 §2.19，M9）
--
-- 库存服务只保存最底层、和库存直接相关的组织（账本），多级组织关系留在宿主：
--   · 账本记下宿主组织的类型（门店 / 连锁 / 主体 / 平台运营方），不同层级的编号可以重号；
--   · 库位可挂宿主的「持有方」标记（如连锁账本里各门店所属的主体），服务只存只滤；
--   · 出库对象若在本系统也有账本（兄弟门店、加盟店），关联到对方账本与收货库位；
--   · 发给这类对象的出库过账时，在对方账本生成待收入库单，一出一入由成对记录关联。

ALTER TABLE inv_owner ADD COLUMN external_type VARCHAR(32) NOT NULL DEFAULT 'ENTITY' COMMENT '宿主组织类型（STORE / CHAIN / ENTITY / PLATFORM 等），服务不解释，只参与唯一';
UPDATE inv_owner SET external_type = 'PLATFORM' WHERE system_code <> 'SHOP';
DROP INDEX uk_owner_system_ext ON inv_owner;
CREATE UNIQUE INDEX uk_owner_system_type_ext ON inv_owner (system_code, external_type, external_ref);
CREATE INDEX idx_owner_system_ext ON inv_owner (system_code, external_ref);

ALTER TABLE inv_location ADD COLUMN holder_ref VARCHAR(64) DEFAULT NULL COMMENT '宿主的持有方标记（如门店所属主体）；服务只存储、只按它过滤';
CREATE INDEX idx_loc_holder ON inv_location (owner_id, holder_ref);

ALTER TABLE inv_target_ref ADD COLUMN linked_owner_id VARCHAR(32) DEFAULT NULL COMMENT '对象在同系统内的账本；非空时发给它的 RECEIPT 出库生成对方待收入库';
ALTER TABLE inv_target_ref ADD COLUMN linked_location_id VARCHAR(32) DEFAULT NULL COMMENT '对方账本中的收货库位，与 linked_owner_id 同时非空';

CREATE TABLE inv_paired_transfer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pair_no VARCHAR(32) NOT NULL,
    system_code VARCHAR(32) NOT NULL,
    from_owner_id VARCHAR(32) NOT NULL COMMENT '发货方账本',
    outbound_no VARCHAR(32) NOT NULL,
    from_location_id VARCHAR(32) NOT NULL,
    to_owner_id VARCHAR(32) NOT NULL COMMENT '收货方账本',
    inbound_no VARCHAR(32) NOT NULL,
    to_location_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'SHIPPED 已发未收 / RECEIVED 已收',
    line_items TEXT NOT NULL COMMENT 'JSON：[{itemRef, fromItemId, toItemId, shipped, received}]',
    shipped_at DATETIME NOT NULL,
    received_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_paired_no (pair_no),
    UNIQUE KEY uk_paired_out (from_owner_id, outbound_no),
    UNIQUE KEY uk_paired_in (to_owner_id, inbound_no),
    KEY idx_paired_from (from_owner_id, status),
    KEY idx_paired_to (to_owner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨账本成对单据：发货方出库与收货方入库的关联';
