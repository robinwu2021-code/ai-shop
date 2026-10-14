-- 供应商收款账户（ADR-011 自营供应商模式 · TDD-供应商结算与双轨资金 §3.1）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么必须新建这张表，而进件时明明已经采过结算账号
-- ─────────────────────────────────────────────────────────────────────────────
-- 进件那条路上账号是「明文只在本次调用中存在，不落库」
-- （PayApplymentGateway.SubmitCommand 的注释），库里只留 settle_account_masked。
-- 那个设计是对的 —— 它的用途是**转交给通道**，平台自己不需要留。
--
-- 自营付款不一样：平台要拿着账号去网银转账，绕不过落库。
-- 所以这里存密文（AES-256-GCM），并且**解密只在「导出付款清单」一个入口发生**。
--
-- ⚠️ 账户挂**主体**不挂门店：收款是主体的事（一张营业执照一个收款人），
-- 门店只是统计维度。stl_bill.store_no 的注释说得很清楚：
-- 「纯统计维度……它不决定钱打给谁」。
--
-- ⚠️ 同一主体**同时只能有一个 ACTIVE**：付款时要能无歧义地回答「打给哪张卡」。
-- 换卡 = 提交新账户 + 运营核过 + 旧的自动置 DISABLED，而不是原地改账号 ——
-- 原地改会让「上一期打给谁」这个问题失去答案。

CREATE TABLE IF NOT EXISTS mch_payout_account
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    account_no VARCHAR(64) NOT NULL COMMENT '平台内部单号',
    entity_no VARCHAR(64) NOT NULL COMMENT '供应商主体。挂主体不挂门店 —— 收款是主体的事',
    account_type VARCHAR(24) NOT NULL COMMENT 'PERSONAL_BANK_CARD 个人银行卡 / CORPORATE 对公。与 sys_legal_form.settle_account_type 同值域',
    account_name VARCHAR(128) NOT NULL COMMENT '户名。三流一致比对用 —— 必须等于主体名与进项票开票方，否则付得出去也入不了账',
    account_number_enc VARCHAR(512) NOT NULL COMMENT '账号密文 base64(iv+密文+tag)。**绝不存明文**：库被读走就是一批供应商的收款账号',
    account_masked VARCHAR(64) NOT NULL COMMENT '掩码（只留尾四位，口径同 Masks.tail）。列表、日志、导出预览一律只用它',
    bank_name VARCHAR(128) DEFAULT NULL COMMENT '开户行',
    bank_branch VARCHAR(128) DEFAULT NULL COMMENT '支行',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审核 / ACTIVE 生效中 / REJECTED 已驳回 / DISABLED 已停用（被新账户顶替）',
    audit_remark VARCHAR(255) DEFAULT NULL COMMENT '驳回原因。**原样回商家 B 端** —— 不写等于让他猜',
    audited_by VARCHAR(64) DEFAULT NULL COMMENT '审核人（STAFF 账号）。改收款账户是资金重定向，必须留痕',
    audited_at BIGINT(20) DEFAULT NULL COMMENT '审核时刻（毫秒）。未审为空',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_payout_account (account_no),
    KEY idx_mch_payout_account_entity (entity_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商收款账户（自营付款用，账号密文存储）';
