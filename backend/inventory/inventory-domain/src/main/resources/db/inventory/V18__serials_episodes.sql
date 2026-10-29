-- 进销存独立服务 · 按 SN 逐件管理（TDD-进销存独立服务与多业态 §2.6 / §2.8 / §2.9 / §2.14，M3）
--
-- 一件设备一份 SN 档案；入库开一个在库周期、出库关闭；回仓永远开新周期，旧周期不重开。
-- 「同一 SN 同时至多一个在仓周期」两道保险：档案上的当前周期号条件更新 + 周期表生成列唯一索引。

CREATE TABLE inv_serial (
    id BIGINT NOT NULL AUTO_INCREMENT,
    serial_id VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    serial_no VARCHAR(64) NOT NULL COMMENT '设备上的序列号',
    active_episode_id VARCHAR(32) DEFAULT NULL COMMENT '当前在库周期；空 = 不在仓',
    parent_serial_id VARCHAR(32) DEFAULT NULL COMMENT '组装关系：挂在哪个上级件下（如宝挂在柜下）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_serial_id (owner_id, serial_id),
    UNIQUE KEY uk_inv_serial_no (owner_id, serial_no),
    KEY idx_inv_serial_parent (owner_id, parent_serial_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SN 档案';

CREATE TABLE inv_stock_episode (
    id BIGINT NOT NULL AUTO_INCREMENT,
    episode_id VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    serial_id VARCHAR(32) NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    location_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'OPEN / CLOSED',
    gate_ref VARCHAR(64) DEFAULT NULL COMMENT '这一轮入库所凭的放行凭证',
    open_doc_no VARCHAR(32) NOT NULL,
    close_doc_no VARCHAR(32) DEFAULT NULL,
    opened_at DATETIME NOT NULL,
    closed_at DATETIME DEFAULT NULL,
    open_serial_id VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 'OPEN' THEN serial_id ELSE NULL END) STORED COMMENT '只在 OPEN 时有值：唯一索引保证同一 SN 至多一个在仓周期',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_stock_episode (owner_id, episode_id),
    UNIQUE KEY uk_inv_stock_episode_open (owner_id, open_serial_id),
    KEY idx_inv_stock_episode_serial (owner_id, serial_id, id),
    KEY idx_inv_stock_episode_location (owner_id, location_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='在库周期：入库开、出库关，回仓开新的';

-- 单据上的 SN 明细。草稿只记 SN 号（不占用），过账时才落 serial_id
CREATE TABLE inv_document_serial (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    doc_kind VARCHAR(8) NOT NULL COMMENT 'IN / OUT',
    doc_no VARCHAR(32) NOT NULL,
    line_no INT NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    serial_no VARCHAR(64) NOT NULL,
    serial_id VARCHAR(32) DEFAULT NULL COMMENT '过账时回填',
    parent_serial_no VARCHAR(64) DEFAULT NULL COMMENT '入库时登记的组装关系 / 出库时展开来源',
    gate_ref VARCHAR(64) DEFAULT NULL COMMENT '入库：这一件的放行凭证',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_document_serial (owner_id, doc_kind, doc_no, serial_no),
    KEY idx_inv_document_serial_sn (owner_id, serial_no),
    KEY idx_inv_document_serial_gate (owner_id, gate_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据 SN 明细';

-- 单据上的目标（收货方 / 接收人），带名称快照：宿主改了名，历史单据不跟着变
CREATE TABLE inv_document_target (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    doc_kind VARCHAR(8) NOT NULL,
    doc_no VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_ref VARCHAR(64) NOT NULL,
    name_snapshot VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_document_target (owner_id, doc_kind, doc_no, target_type),
    KEY idx_inv_document_target_ref (owner_id, target_type, target_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据目标与名称快照';

-- 入库：期初依据与附件、放行凭证；单据记下所用档案版本
ALTER TABLE inv_inbound_order ADD COLUMN basis VARCHAR(32) DEFAULT NULL COMMENT '期初依据：INVOICE / DELIVERY_NOTE / CONTRACT / COUNT / MIGRATION / OTHER';
ALTER TABLE inv_inbound_order ADD COLUMN attachment_refs VARCHAR(1024) DEFAULT NULL COMMENT '附件引用，逗号分隔';
ALTER TABLE inv_inbound_order ADD COLUMN profile_version INT DEFAULT NULL COMMENT '过账时所用业态档案版本';
ALTER TABLE inv_inbound_line ADD COLUMN gate_ref VARCHAR(64) DEFAULT NULL COMMENT '数量物料：这一行的放行凭证';
ALTER TABLE inv_outbound_order ADD COLUMN profile_version INT DEFAULT NULL COMMENT '过账时所用业态档案版本';
