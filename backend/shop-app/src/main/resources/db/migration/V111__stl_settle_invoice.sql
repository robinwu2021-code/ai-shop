-- 商家结算发票申请（矩阵 P-12.2.4 结算凭证/发票）。
--
-- ⚠️ **这是第三个方向的票，前两个都已经有表了，别合并**：
--   stl_purchase_invoice  供应商 → 平台（进项，自营应付，P0-8）
--   ord_invoice_request   平台   → 消费者（销项，C 端申请，V94）
--   stl_settle_invoice    平台   → 商家（本表：商家拿结算款要的凭证）
-- 三张票的开票方、受票方、金额来源、税务后果各不相同。
-- 合成一张表的代价是「谁欠谁」这件事再也说不清 —— 而那正是发票唯一要回答的问题。
--
-- 状态机：
--   PENDING  已申请，等运营开
--   ISSUED   已开具（回填 serial_no）
--   REJECTED 驳回，必须写原因
CREATE TABLE IF NOT EXISTS stl_settle_invoice
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    invoice_no VARCHAR(64) NOT NULL COMMENT '开票申请单号',
    entity_no VARCHAR(64) NOT NULL COMMENT '申请商家',
    merchant_name VARCHAR(128) DEFAULT NULL COMMENT '商家名快照',
    period VARCHAR(16) NOT NULL COMMENT '开票周期 YYYY-MM，与结算周期同口径',
    amount_minor BIGINT(20) NOT NULL COMMENT '申请开票金额（分）',
    settled_amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '该周期已结算金额快照（分）。开票金额不得超过它 —— 超出部分没有真实交易对应，就是虚开。落快照是因为后续退款会改结算额，而已开的票不会跟着变',
    title_type VARCHAR(16) NOT NULL DEFAULT 'COMPANY' COMMENT 'COMPANY 企业 / PERSONAL 个人。企业抬头必须有税号，个人抬头没有 —— 这是两条不同的校验路径',
    title VARCHAR(128) NOT NULL COMMENT '发票抬头（公司全称或个人姓名）',
    tax_no VARCHAR(32) DEFAULT NULL COMMENT '纳税人识别号。企业抬头必填',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    serial_no VARCHAR(64) DEFAULT NULL COMMENT '发票流水号，开完回填',
    applied_at BIGINT(20) NOT NULL COMMENT '申请时刻（毫秒）',
    decided_at BIGINT(20) DEFAULT NULL COMMENT '处理时刻。未处理为空',
    decided_by VARCHAR(64) DEFAULT NULL COMMENT '经办人 —— 手工开票必须留痕，否则事后查不到是谁开的',
    remark VARCHAR(255) DEFAULT NULL COMMENT '驳回原因。原样回商家 B 端',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_settle_invoice (invoice_no),
    -- 同一周期同一商家只能有一张：重复申请 = 一笔结算两张票，那是税务问题不是体验问题。
    -- 改抬头走「驳回后重申请」，不靠再插一条
    UNIQUE KEY uk_stl_settle_invoice_period (entity_no, period, deleted),
    KEY idx_stl_settle_invoice_status (status, applied_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商家结算发票申请（P-12.2.4）';
