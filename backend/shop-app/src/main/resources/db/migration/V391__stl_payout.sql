-- =====================================================================
-- V391 放款记录（TDD-账期推进与放款记录 批 2 / ADR-011 §7）
--
-- 一笔网银转账一条。此前「付款」挂在逐张结算单上（stl_bill.payment_ref），
-- 财务一笔转账要逐张回填同一个凭证号 —— 出款对账的 DUP_REF（同号多单）在正常操作下就会触发，
-- 它分不清「复制粘贴错了」和「本来就是同一笔转账」。
--
-- 粒度 = 账期批次 × 收款号。户名/开户行是**付款那一刻的快照**：银行要户名，而账号会改。
-- 刻意没做的：
-- - 不删 stl_withdraw：撤入口与菜单（批 3），表与服务留着（生产 0 行）。
-- - 不加 channel 以外的银行 API 字段：一期全 MANUAL，接口来了只换 EXPORTED→PAID 那一步。
-- =====================================================================

CREATE TABLE IF NOT EXISTS stl_payout
(
    id                BIGINT(20)   NOT NULL AUTO_INCREMENT,
    payout_no         VARCHAR(64)  NOT NULL COMMENT '放款单号（PO…）',
    batch_no          VARCHAR(64)  NOT NULL COMMENT '所属账期批次',
    entity_no         VARCHAR(64)  NOT NULL COMMENT '收款主体业务键',
    pay_merchant_no   VARCHAR(64)  DEFAULT NULL COMMENT '收款号（分组键）。自营一主体一账户时为空',
    account_name      VARCHAR(128) DEFAULT NULL COMMENT '付款时快照：户名。银行校验用',
    bank_name         VARCHAR(64)  DEFAULT NULL COMMENT '付款时快照：开户行',
    bank_branch       VARCHAR(128) DEFAULT NULL COMMENT '付款时快照：支行',
    account_no_masked VARCHAR(32)  DEFAULT NULL COMMENT '付款时快照：账号掩码。明文只在导出清单那一刻存在',
    amount_minor      BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '= 本组结算单 net 之和（分）',
    bill_count        INT(11)      NOT NULL DEFAULT 0,
    currency          VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待导出 / EXPORTED 已进付款清单 / PAID 已登记凭证 / MATCHED 银行流水已勾上 / FAILED 打款失败或退回',
    channel           VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 网银手工 / BANK_API 预留',
    payment_ref       VARCHAR(64)  DEFAULT NULL COMMENT '凭证号（网银流水号）。登记付款时必填',
    bank_flow_no      VARCHAR(64)  DEFAULT NULL COMMENT '对上的银行流水号（stl_bank_flow.flow_no）',
    exported_at       BIGINT(20)   DEFAULT NULL,
    paid_at           BIGINT(20)   DEFAULT NULL,
    paid_by           VARCHAR(64)  DEFAULT NULL,
    matched_at        BIGINT(20)   DEFAULT NULL,
    fail_reason       VARCHAR(512) DEFAULT NULL COMMENT '退回/失败原因。给运营看，也给商家看',
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)  DEFAULT NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)  DEFAULT NULL,
    version           BIGINT(20)   NOT NULL DEFAULT 0,
    deleted           TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_payout_no (payout_no, tenant_no),
    KEY idx_stl_payout_batch (batch_no),
    KEY idx_stl_payout_status (status, entity_no),
    KEY idx_stl_payout_ref (payment_ref)
) COMMENT='放款记录：账期批次 × 收款号一笔，凭证号与银行流水都挂在它上面';

ALTER TABLE stl_bill
    ADD COLUMN payout_no VARCHAR(64) DEFAULT NULL COMMENT '这张单在哪笔放款里。为空 = 还没放款，或走的是老的逐张付款路（存量）';

ALTER TABLE stl_bill ADD KEY idx_stl_bill_payout (payout_no);

ALTER TABLE stl_bank_flow
    ADD COLUMN matched_payout_no VARCHAR(64) DEFAULT NULL COMMENT '勾上的放款单。matched_settle_no 留给存量的逐张付款';
