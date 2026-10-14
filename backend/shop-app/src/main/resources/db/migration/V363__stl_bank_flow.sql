-- 银行流水镜像（ADR-011 · TDD-供应商结算与双轨资金 §3.4 · P3）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 它补的是出款对账的 B 侧
-- ─────────────────────────────────────────────────────────────────────────────
-- PayoutReconAxis 今天只有 A 侧（我方自查：已付款却没有流水号、同一流水号出现在
-- 多张单上）。**「银行到底有没有划出这笔」看不见** —— 而那正是出款对账要答的问题。
--
-- 一期走**人工上传 CSV**（网银导出的流水），不接银企直连：
-- 直连要银行侧开通与联调，而没有它这条轴就一直是半条。先用人工的把闭环走通，
-- 直连接上时换的是导入那一段，比对逻辑一行不动。
--
-- ⚠️ **flow_no 唯一**：同一份流水重复上传是常态（财务会手滑），
-- 而重复入库会让「银行有而系统没登记」凭空多出一批差异。
-- 唯一键让重复上传变成幂等，不需要人去记「这份传过没有」。
--
-- ⚠️ 金额一律**分**，且**出账为正**。银行导出的 CSV 里借贷方向各家写法不同
-- （借/贷、支出/收入、负数），那一层在导入时归一化 —— 让方言停在解析器里，
-- 不要流进比对逻辑。

CREATE TABLE IF NOT EXISTS stl_bank_flow
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    flow_no VARCHAR(64) NOT NULL COMMENT '银行流水号。**与 stl_bill.payment_ref 对勾的就是它**',
    trade_date VARCHAR(10) NOT NULL COMMENT '交易日期 yyyy-MM-dd。按日对账，不需要时分秒',
    direction VARCHAR(8) NOT NULL COMMENT 'OUT 付出 / IN 收入。出款对账只看 OUT，IN 留着是因为同一份流水里两种都有',
    amount_minor BIGINT(20) NOT NULL COMMENT '金额（分），**恒为正**。方向看 direction，别用正负号表示',
    counterparty_name VARCHAR(128) DEFAULT NULL COMMENT '对方户名。核对「钱是不是打给了这家供应商」',
    counterparty_account_masked VARCHAR(64) DEFAULT NULL COMMENT '对方账号掩码。**只存掩码** —— 对账不需要全号',
    remark VARCHAR(255) DEFAULT NULL COMMENT '银行附言/摘要。导出付款清单时写的是「货款-{主体号}」，回读时它是第二条勾对线索',
    matched_settle_no VARCHAR(64) DEFAULT NULL COMMENT '勾对上的结算单号；空 = 还没勾上（可能是差异，也可能只是还没扫到）',
    imported_by VARCHAR(64) DEFAULT NULL COMMENT '谁传的。银行流水是对账的判据，判据从哪来要留痕',
    imported_at BIGINT(20) NOT NULL COMMENT '导入时刻（毫秒）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_bank_flow (flow_no),
    KEY idx_stl_bank_flow_date (trade_date, direction),
    KEY idx_stl_bank_flow_matched (matched_settle_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='银行流水镜像（人工上传，出款对账 B 侧）';
