-- 账期批次：钱按批放，过了三道对账门才放。
--
-- 方案见 docs/technical/design/账期与对账放款-方案.md 与 TDD-支付域-数据库设计.md。
--
-- 为什么要有批次这个对象：没有它，「这个账期对完了没有」无处安放，
-- 「这一单卡在哪一批」也查不出来。今天结算单生成之后就没有任何东西推动它，
-- 而推动它的前提是先有一个「这一批可以放了」的判断。
--
-- **批次管「能不能放」，单据管「放得成不成」** —— 两件事不塞进一个状态机。
-- 所以 stl_bill.status 不加新取值（FROZEN_BACK 是另一回事，随分账下发那一批加）。

CREATE TABLE IF NOT EXISTS stl_settle_batch
(
    id               BIGINT(20)   NOT NULL AUTO_INCREMENT,
    batch_no         VARCHAR(64)  NOT NULL COMMENT '批次号',
    entity_no        VARCHAR(64)  NOT NULL COMMENT '主体业务键。跨库引用只认业务键，不认自增 id',
    pay_channel      VARCHAR(16)  NOT NULL COMMENT '一个主体在不同通道各自成批：账期与费率都按通道走',
    settle_cycle     VARCHAR(16)  NOT NULL DEFAULT 'T+1' COMMENT '本批采用的账期规则快照。配置会变，历史账不能跟着变',
    period_from      BIGINT(20)   NOT NULL COMMENT '收单区间起（含），毫秒',
    period_to        BIGINT(20)   NOT NULL COMMENT '收单区间止（不含），毫秒',
    due_at           BIGINT(20)   NOT NULL COMMENT 'T3 应结日：按账期规则从 T2 推出',
    released_at      BIGINT(20)   DEFAULT NULL COMMENT '实际放行时刻。与 due_at 分开才答得出「晚了几天、晚在哪一段」',
    -- 取本批**最早一单**的成交时刻 + 通道冻结窗口。取平均或取最晚都会让告警晚于实际到期：
    -- 整批一起放，而最早的那一笔先到期，它到期就意味着这一批已经出问题了
    freeze_expire_at BIGINT(20)   DEFAULT NULL COMMENT 'Tmax。到期未放行则本批必然产生 FROZEN_BACK',
    status           VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/COLLECTED/RECONCILING/BLOCKED/RECONCILED/RELEASED',
    bill_count       INT(11)      NOT NULL DEFAULT 0,
    gross_minor      BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '本批结算基数合计（分）',
    net_minor        BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '本批应放款合计（分）',
    -- 过渡期只有 A 侧（我方自查），界面据此如实标注，不显示成「已对账」。
    -- 没有 B 侧时说「已对账」是一句自证的话
    recon_scope      VARCHAR(16)  NOT NULL DEFAULT 'SELF_ONLY' COMMENT 'SELF_ONLY 仅我方自查 / BOTH 含对方账单',
    blocked_reason   VARCHAR(512) DEFAULT NULL COMMENT '挂起原因。**直接展示给商家**，要含具体数字与阈值',
    blocked_at       BIGINT(20)   DEFAULT NULL,
    -- 没有时限的挂起等于永久冻结，而它会以「还在排查」的形式一直存在
    block_expire_at  BIGINT(20)   DEFAULT NULL COMMENT '挂起时限。超时自动放行并告警',
    decided_by       VARCHAR(64)  DEFAULT NULL COMMENT '人工放行者；超时放行写 SYSTEM_TIMEOUT',
    decide_remark    VARCHAR(512) DEFAULT NULL COMMENT '人工放行/继续挂起都必须写原因',
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME     NOT NULL,
    created_by       VARCHAR(64)  DEFAULT NULL,
    updated_at       DATETIME     NOT NULL,
    updated_by       VARCHAR(64)  DEFAULT NULL,
    version          BIGINT(20)   NOT NULL DEFAULT 0,
    deleted          TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_batch_no (batch_no, tenant_no),
    UNIQUE KEY uk_stl_batch_period (entity_no, pay_channel, period_from, tenant_no, deleted),
    KEY idx_stl_batch_due (status, due_at),
    KEY idx_stl_batch_freeze (status, freeze_expire_at)
) COMMENT='账期批次：一个主体一个通道一个账期一批';

-- uk_stl_batch_period 是防重复开批的那道锁：截批任务重跑一次就会把同一区间的单
-- 分进两批，而两批各自放行 —— 那是给商家打两次钱。靠唯一键而不靠应用层判断。

ALTER TABLE stl_bill
    ADD COLUMN settleable_at BIGINT(20) DEFAULT NULL COMMENT 'T2 可结算时刻 = 履约完成 + 售后期。为空 = 还不可结算（未履约或售后未闭环）';

ALTER TABLE stl_bill
    ADD COLUMN batch_no VARCHAR(64) DEFAULT NULL COMMENT '归属账期批次。为空 = 还没入批；查「这单卡在哪」全靠它';

ALTER TABLE stl_bill ADD KEY idx_stl_bill_settleable (status, settleable_at);

ALTER TABLE stl_bill ADD KEY idx_stl_bill_batch (batch_no);

-- 账期落在进件档案上而不是 mch_entity：后者是主体主档，而账期天然是
-- 「主体 × 通道」二维的 —— 一家同时开微信和支付宝，两边的账期可以不同。
-- 且支付域将来独立成库时 mch_payment_merchant 会一起过去，放这里免得跨库读配置。
ALTER TABLE mch_payment_merchant
    ADD COLUMN settle_cycle VARCHAR(16) NOT NULL DEFAULT 'T+1' COMMENT '本主体在本通道的账期。上限受 sys_pay_channel.settle_cycle 约束，取更短的那个';
