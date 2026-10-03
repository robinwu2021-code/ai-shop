-- 商家欠款：退款追不回来时，先记在账上，从后续货款里扣。
--
-- 方案见 docs/technical/design/账期与对账放款-方案.md §三（Z4 追偿三层）。
-- 三层是：① 保证金扣划 → ② 后续货款抵扣（就是这两张表）→ ③ 停止放款转人工。
--
-- **不并进保证金表（mch_deposit）。** 保证金是商家的钱（平台代管，将来要退还），
-- 欠款是商家欠平台的钱 —— 方向相反。合在一张表上用正负号表达的话，
-- 「应退还多少保证金」这个问题就永远算不清了，而那是退店结账时要给出的数。

CREATE TABLE IF NOT EXISTS mch_debt
(
    id                   BIGINT(20)  NOT NULL AUTO_INCREMENT,
    entity_no            VARCHAR(64) NOT NULL,
    -- 方向单一：欠款只会 >= 0。出现负数说明有 bug，不是「预付」
    balance_minor        BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '当前欠款（分），恒 >= 0',
    total_incurred_minor BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '累计产生（分），只增',
    total_repaid_minor   BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '累计已偿（分），只增',
    last_incurred_at     BIGINT(20)  DEFAULT NULL COMMENT '最近一次产生欠款的时刻',
    tenant_no            VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at           DATETIME    NOT NULL,
    created_by           VARCHAR(64) DEFAULT NULL,
    updated_at           DATETIME    NOT NULL,
    updated_by           VARCHAR(64) DEFAULT NULL,
    version              BIGINT(20)  NOT NULL DEFAULT 0,
    deleted              TINYINT(4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_debt_entity (entity_no, tenant_no)
) COMMENT='商家欠款账户，一主体一行。与保证金方向相反，不合表';

-- 只有余额字段的账户是不可审计的：出现争议时说不清「这笔钱什么时候欠的、
-- 凭什么欠、从哪一批扣的」，而欠款恰恰是最会被争的一笔。
CREATE TABLE IF NOT EXISTS mch_debt_txn
(
    id                  BIGINT(20)   NOT NULL AUTO_INCREMENT,
    txn_no              VARCHAR(64)  NOT NULL,
    entity_no           VARCHAR(64)  NOT NULL,
    txn_type            VARCHAR(16)  NOT NULL
        COMMENT 'INCUR 产生 / OFFSET 货款抵扣 / DEPOSIT 保证金抵扣 / WRITE_OFF 核销',
    -- 有符号：产生为正、偿还为负。靠 txn_type 推方向等于把方向表达两遍，
    -- 两处一旦不一致就没法判定谁对
    amount_minor        BIGINT(20)   NOT NULL COMMENT '变动额（分），可为负',
    balance_after_minor BIGINT(20)   NOT NULL COMMENT '变动后欠款余额（分），对账用',
    -- 每一笔欠款都要指得出源头。指不出源头的欠款没法向商家解释
    source_type         VARCHAR(16)  DEFAULT NULL COMMENT 'REFUND 退款追偿 / OTHER',
    source_no           VARCHAR(64)  DEFAULT NULL COMMENT '源单号：售后单号 / 结算单号',
    batch_no            VARCHAR(64)  DEFAULT NULL COMMENT 'OFFSET 时记从哪一批扣的',
    reason              VARCHAR(512) DEFAULT NULL,
    operator            VARCHAR(64)  DEFAULT NULL,
    tenant_no           VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at          DATETIME     NOT NULL,
    created_by          VARCHAR(64)  DEFAULT NULL,
    updated_at          DATETIME     NOT NULL,
    updated_by          VARCHAR(64)  DEFAULT NULL,
    version             BIGINT(20)   NOT NULL DEFAULT 0,
    deleted             TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_debt_txn_no (txn_no, tenant_no),
    UNIQUE KEY uk_mch_debt_txn_source (entity_no, source_type, source_no, tenant_no, deleted),
    KEY idx_mch_debt_txn_entity (entity_no, tenant_no)
) COMMENT='欠款流水。只有余额字段的账户是不可审计的';

-- uk_mch_debt_txn_source 与保证金流水的差别在这里：保证金的变动是**人**发起的
-- （缴纳、扣划），欠款的变动是**事件**发起的（退款追偿），而事件会重投。
-- 没有这道唯一键，一次重投就让商家凭空多欠一笔。
