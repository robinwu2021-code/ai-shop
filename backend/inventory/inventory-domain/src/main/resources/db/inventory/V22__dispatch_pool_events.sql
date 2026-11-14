-- 进销存独立服务 · 出库池与事件拉取（TDD-进销存独立服务与多业态 §2.9 / §2.12，M4）
--
-- 出库池是交接凭证，不是库存：交接出库过账时逐件 / 逐行生成；待签收可调整、撤回；签收即交接，
-- 之后库存不再跟踪。事件由宿主按游标拉取：业务事务里只写「待排序」，提交之后再分配序号 ——
-- 按自增 id 拉会漏掉晚提交的小 id。

CREATE TABLE inv_dispatch_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dispatch_id VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    outbound_no VARCHAR(32) NOT NULL,
    line_no INT NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    location_id VARCHAR(32) NOT NULL COMMENT '从哪个库位发出（撤回时回到这里）',
    serial_id VARCHAR(32) DEFAULT NULL,
    serial_no VARCHAR(64) DEFAULT NULL,
    episode_id VARCHAR(32) DEFAULT NULL COMMENT '逐件：发出时关闭的那一个在库周期',
    qty DECIMAL(24,6) NOT NULL,
    parent_dispatch_id VARCHAR(32) DEFAULT NULL COMMENT '成套：从属记录（宝）随主记录（柜）调整 / 撤回 / 签收',
    root_target_type VARCHAR(32) NOT NULL COMMENT '收货方层级最顶的目标（如商家），按它查与汇总在途',
    root_target_ref VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'AVAILABLE / CLAIMED / REVOKED / RETURNED',
    resource_version INT NOT NULL DEFAULT 1 COMMENT '每次状态或目标变化 +1；事件带它，宿主丢弃旧版本',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_dispatch_item (owner_id, dispatch_id),
    UNIQUE KEY uk_inv_dispatch_item_episode (owner_id, episode_id),
    KEY idx_inv_dispatch_item_root (owner_id, root_target_type, root_target_ref, status),
    KEY idx_inv_dispatch_item_serial (owner_id, serial_id, id),
    KEY idx_inv_dispatch_item_outbound (owner_id, outbound_no),
    KEY idx_inv_dispatch_item_parent (owner_id, parent_dispatch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库池：交接凭证，不是库存';

-- 出库池记录的当前目标（调整会改它；单据上的 inv_document_target 是建单时的快照，不改）
CREATE TABLE inv_dispatch_target (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    dispatch_id VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_ref VARCHAR(64) NOT NULL,
    name_snapshot VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_dispatch_target (owner_id, dispatch_id, target_type),
    KEY idx_inv_dispatch_target_ref (owner_id, target_type, target_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库池记录的当前目标';

-- 领取：每条池记录至多一个有效领取（生成列唯一索引）
CREATE TABLE inv_dispatch_claim (
    id BIGINT NOT NULL AUTO_INCREMENT,
    claim_no VARCHAR(32) NOT NULL COMMENT '一次签收批次一个号',
    owner_id VARCHAR(32) NOT NULL,
    dispatch_id VARCHAR(32) NOT NULL,
    system_code VARCHAR(32) NOT NULL COMMENT '领取系统：只有它能释放',
    request_id VARCHAR(64) DEFAULT NULL,
    business_ref VARCHAR(64) DEFAULT NULL COMMENT '宿主的业务引用（签收单号等）',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / RELEASED / CLOSED',
    active_dispatch_id VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN dispatch_id ELSE NULL END) STORED,
    released_at DATETIME DEFAULT NULL,
    released_by VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_dispatch_claim_active (owner_id, active_dispatch_id),
    KEY idx_inv_dispatch_claim_no (owner_id, claim_no),
    KEY idx_inv_dispatch_claim_request (owner_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库池领取';

-- 调整 / 撤回的前后快照，只追加
CREATE TABLE inv_dispatch_adjust (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    dispatch_id VARCHAR(32) NOT NULL,
    action VARCHAR(16) NOT NULL COMMENT 'REDIRECT / REVOKE',
    before_body TEXT DEFAULT NULL,
    after_body TEXT DEFAULT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_inv_dispatch_adjust (owner_id, dispatch_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库池调整与撤回快照';

-- 事件：业务事务里写待排序，提交后由排序器搬进日志并分配序号
CREATE TABLE inv_event_pending (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    resource_version INT NOT NULL,
    payload TEXT DEFAULT NULL,
    actor VARCHAR(64) DEFAULT NULL,
    trace_id VARCHAR(64) DEFAULT NULL,
    occurred_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_event_pending (event_id),
    KEY idx_inv_event_pending_owner (owner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待排序事件';

CREATE TABLE inv_event_log (
    seq BIGINT NOT NULL AUTO_INCREMENT COMMENT '提交后分配：宿主按它拉取，不漏不重',
    event_id VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    resource_version INT NOT NULL,
    payload TEXT DEFAULT NULL,
    actor VARCHAR(64) DEFAULT NULL,
    trace_id VARCHAR(64) DEFAULT NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (seq),
    UNIQUE KEY uk_inv_event_log (event_id),
    KEY idx_inv_event_log_owner (owner_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件日志（宿主拉取）';

CREATE TABLE inv_event_subscription (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    system_code VARCHAR(32) NOT NULL,
    acked_seq BIGINT NOT NULL DEFAULT 0 COMMENT '宿主确认到哪了',
    pruned_seq BIGINT NOT NULL DEFAULT 0 COMMENT '这个序号及之前已清理；游标落在它之前 = 过期',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_event_subscription (owner_id, system_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件订阅与游标';
