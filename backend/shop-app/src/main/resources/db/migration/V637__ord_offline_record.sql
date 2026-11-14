-- 商家自报的 App 外收款（TDD-商户App外部记账）：现金、外部刷卡机、银行转账在 App 里记一笔账。
--
-- 只是商家自己看、留痕的流水：平台不经手、不进拨款、不进通道对账、不能退款、不改订单状态、不动库存。
-- 所以是 trade 域一张独立的表，不写 pay 域任何表；记录号前缀 OFR 与支付单号空间不重叠。
-- 事件表只插不改：记录表保存当前值，事件表保存每一步的前后值（含更正时改的实收时间，已拍板 D10）。
-- 带 DDL：166 应用库账号无建表权限，需 root 先执行。

CREATE TABLE ord_offline_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    record_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    method VARCHAR(16) NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    note VARCHAR(400) NULL,
    received_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    corrected TINYINT NOT NULL DEFAULT 0,
    recorded_by VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    idem_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offline_record_no (tenant_no,record_no),
    UNIQUE KEY uk_offline_record_idem (tenant_no,entity_no,store_no,idem_key_hash),
    KEY idx_offline_record_store_time (tenant_no,store_no,received_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Merchant self-reported receipts outside the App: no pay ledger, no channel reconciliation, no refund';

CREATE TABLE ord_offline_record_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    record_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    action VARCHAR(16) NOT NULL,
    from_amount_minor BIGINT NULL,
    to_amount_minor BIGINT NULL,
    from_method VARCHAR(16) NULL,
    to_method VARCHAR(16) NULL,
    from_received_at DATETIME(3) NULL,
    to_received_at DATETIME(3) NULL,
    reason VARCHAR(400) NULL,
    actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_offline_record_event (tenant_no,record_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only trail of offline record create, correct and void';

-- 预置角色模板（与 BizPerms.ROLE_PERMS 同步；判权读的是 mch_role，不是代码里的表）：
-- 店长能记、能更正作废；店员只能记。自定义角色不动，由店主自己勾。
-- 列表读复用 mch:pay:order:view：BizPerms 早已把它给了店长、店员，但从没有迁移把它写进 mch_role，
-- 于是线上店员、店长调 /biz/payment/orders 与本组列表都是 70006（BizRoleSeedTest 在基线上就为此红）。这里一并补上。
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:order:view'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:order:view'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:order:view'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='CLERK' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:order:view'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:offline:record'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:offline:record'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:offline:manage'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:offline:manage'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:offline:record'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='CLERK' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:offline:record'))=0;
