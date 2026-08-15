-- 商家提现单（矩阵 P-12.2.1 提现审批 / 12.2.2 限额）。
--
-- ⚠️ **这张表不打款。** 分账参数的书面口径还没拿到（待完成功能清单 §四 B7、
-- ADR-002 §6 待确认第 1 条），而 B-12.5 定的是「一期只记账、线下结算」。
-- 所以它记的是**审批与留痕**：谁在什么时候、按什么余额口径、批了多少钱。
-- 实际出款由财务线下执行，接通道那一批再补 PAID/FAILED 的回执入口。
--
-- 状态机（与 ops-web WITHDRAW_TRANSITIONS 逐字一致）：
--   PENDING  已申请，等审批
--   APPROVED 已通过（**不等于已打款**）
--   REJECTED 已驳回，必须写原因 —— 原文回商家 B 端
--   PAID     渠道回执确认已到账（本批没有生产者，见上）
--   FAILED   渠道回执确认失败，可重新审批（多半是账户信息要改）
--
-- APPROVED → PAID 刻意**不给人工入口**：打款结果只能来自回执。
-- 让人手动置为「已打款」，等于允许在钱没到账时把单子做平，
-- 而之后对账差额永远说不清是通道慢了还是有人点早了。
CREATE TABLE IF NOT EXISTS stl_withdraw
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    withdraw_no VARCHAR(64) NOT NULL COMMENT '提现单号',
    entity_no VARCHAR(64) NOT NULL COMMENT '申请主体（商家）',
    merchant_name VARCHAR(128) DEFAULT NULL COMMENT '商家名快照 —— 商家改名不该让历史提现单跟着变',
    amount_minor BIGINT(20) NOT NULL COMMENT '申请金额（分）',
    available_balance_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '申请时的可提余额快照。**不是实时值** —— 审批看的是「申请那一刻他能提多少」，实时值会因为期间的新订单而漂移',
    bank_account_masked VARCHAR(64) DEFAULT NULL COMMENT '收款账户掩码。**只存掩码**：运营端展示不需要全号，而全号一旦落库就要按敏感信息管',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    applied_at BIGINT(20) NOT NULL COMMENT '申请时刻（毫秒）',
    decided_at BIGINT(20) DEFAULT NULL COMMENT '审批时刻。未审为空',
    decided_by VARCHAR(64) DEFAULT NULL COMMENT '审批人（STAFF 账号）—— 这是运营端唯一会把钱批出去的动作，必须留痕',
    remark VARCHAR(255) DEFAULT NULL COMMENT '驳回原因 / 大额复核说明。**原样回商家 B 端**，不写等于让人猜',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_withdraw (withdraw_no),
    KEY idx_stl_withdraw_status (status, applied_at),
    KEY idx_stl_withdraw_entity (entity_no, applied_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商家提现单（P-12.2.1，只记账不打款）';
