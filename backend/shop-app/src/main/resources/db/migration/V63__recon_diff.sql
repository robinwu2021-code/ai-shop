-- 对账差异（[对账差异-方案] 第一步）。
--
-- `stl_payment.reconciled_at` 的注释从建库起就写着「掉单只能靠对账发现，没有别的手段」，
-- 而这一列**至今没有一次赋值** —— 那条唯一的发现手段是空的。
--
-- 本表先建，产出方分两批接：
--   第一批（本次）：平台侧自查 —— PENDING 超过关单时限的收款，逐笔向通道查单，
--                   通道说已付就补回调、说未付就关单，两边不一致的落成差异行；
--   第二批（待通道账单能力）：按日拉渠道账单逐笔比对，产出 CHANNEL_ONLY / AMOUNT_DIFF。
--
-- **为什么表现在就建**：表不是贵的部分，账单下载才是。表先立起来，
-- 自查产出的差异当场就能被处置、被记结论；等账单接上，只是多一个产出方，
-- 页面与处置流程一行不用改。

CREATE TABLE IF NOT EXISTS stl_recon_diff
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    diff_no VARCHAR(64) NOT NULL COMMENT '差异单业务键',
    bill_date VARCHAR(10) NOT NULL COMMENT '账期 YYYY-MM-DD。自查产出的记扫描日',
    pay_channel VARCHAR(16) NOT NULL COMMENT 'WECHAT / ALIPAY',

    diff_type VARCHAR(24) NOT NULL COMMENT 'CHANNEL_ONLY 渠道有我方无（掉单）/ PLATFORM_ONLY 我方有渠道无 / AMOUNT_DIFF 金额不符',
    source VARCHAR(16) NOT NULL DEFAULT 'SELF_CHECK' COMMENT 'SELF_CHECK 平台侧自查 / CHANNEL_BILL 渠道账单比对。**页面要按它标注覆盖范围** —— 只有自查的时候，渠道侧那一整类差异是看不见的',

    payment_no VARCHAR(64) DEFAULT NULL COMMENT '我方流水号。CHANNEL_ONLY 时为空（我方根本没这条）',
    order_no VARCHAR(64) DEFAULT NULL,
    channel_txn_no VARCHAR(64) DEFAULT NULL COMMENT '通道流水号。PLATFORM_ONLY 时为空',

    channel_amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '通道侧金额。PLATFORM_ONLY 为 0',
    platform_amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '我方金额。CHANNEL_ONLY 为 0',

    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处置 / RESOLVED 已处置 / IGNORED 已忽略',
    resolution VARCHAR(255) DEFAULT NULL COMMENT '处置结论。RESOLVED / IGNORED **必填** —— 没有结论的「已处理」等于没处理，下个月对账时没人知道当时为什么放过它',
    recovered_order_no VARCHAR(64) DEFAULT NULL COMMENT '处置产生的补单号',

    resolved_at BIGINT(20) DEFAULT NULL,
    resolved_by VARCHAR(64) DEFAULT NULL,

    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_diff_no (diff_no),
    -- 同一笔流水在同一账期、**同一类型**只该有一条：自查每 10 分钟跑一轮，
    -- 不去重的话一笔掉单会在列表里堆成几十条，而运营要逐条点开才知道是同一笔。
    -- 键里必须含 diff_type —— 一笔单可以既是掉单又是金额不符，那是两件要分别处置的事，
    -- 不含类型的话第二条会被唯一键挤掉，而挤掉的恰恰是「钱数不对」这条
    UNIQUE KEY uk_recon_diff_payment (bill_date,pay_channel,payment_no,diff_type),
    KEY idx_recon_diff_status (status,bill_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='对账差异：平台侧自查与渠道账单比对的产出，逐条人工裁决';
