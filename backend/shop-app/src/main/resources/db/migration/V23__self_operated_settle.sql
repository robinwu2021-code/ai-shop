-- 自营经营模式与自营结算链路（经营模式双轨方案 P0-1~5）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么经营模式要挂在门店上，而不是主体上
-- ─────────────────────────────────────────────────────────────────────────────
-- 同一个主体下，旗舰店做自营、加盟店做第三方是常见形态。挂主体的话，
-- 一家主体只能整体二选一 —— 而这个选择的依据（品类、供货方资质、履约控制）
-- 本来就是按店不同的。
--
-- ⚠️ 与 settle_mode（分账时机）是**两个正交的轴**，不要合并：
--    business_mode  钱是谁的        —— 决定凭证、税务、能否提现到对公
--    settle_mode    什么时候给供货方 —— 仅 THIRD_PARTY 有意义（本次不建，见 P1）
-- 合成一个枚举之后，「自营 + 直连分账」这种非法组合在类型上就是可表达的，
-- 只能靠运行时校验挡；分成两个字段，非法组合在语义上不存在。

ALTER TABLE mch_store ADD COLUMN business_mode VARCHAR(16) NOT NULL DEFAULT 'SELF_OPERATED'
    COMMENT 'SELF_OPERATED 自营（平台是销售主体）/ THIRD_PARTY 第三方。默认自营 —— 没有 EDI 时只能自营';

-- ─────────────────────────────────────────────────────────────────────────────
-- 结算单：一张表承载两条轨道
-- ─────────────────────────────────────────────────────────────────────────────
-- 不拆表：两条轨道的字段需求高度重叠，拆开会让对账要 union 两张表。
-- 差别用 business_mode 快照区分，它决定这张单走哪条状态机：
--   自营   PENDING_RECON → CONFIRMED → PAID      （对账 → 确认 → 财务付款）
--   第三方 PENDING → SPLITTABLE → SPLIT          （已有）
--
-- **快照而不是每次 join 回门店去查**，理由与 V14 给 pay_merchant_no 快照一致，
-- 且后果更重：门店的经营模式改了，未结的历史流水**不能跟着改口径** ——
-- 自营的单要收进项票、第三方的单不用，走错分支的结果是凭证对不上账。
ALTER TABLE stl_bill ADD COLUMN business_mode VARCHAR(16) NOT NULL DEFAULT 'SELF_OPERATED'
    COMMENT '下单时的经营模式快照。**决定这张单走哪条状态机**';

-- 自营专用：付款登记。
--
-- 系统**只登记不划转** —— 自营的打款是财务在网银执行的，让业务系统去动公司对公账户
-- 是财务内控问题，不是技术能力问题。这两列存的是「财务说他付了，凭证在这里」。
ALTER TABLE stl_bill ADD COLUMN payment_ref VARCHAR(64) DEFAULT NULL
    COMMENT '付款凭证号（网银流水号）。自营专用；空 = 尚未付款';
ALTER TABLE stl_bill ADD COLUMN paid_at BIGINT(20) DEFAULT NULL
    COMMENT '财务登记的付款时间。与 split_at 分开 —— 那是分账时间，两条轨道不共用';

-- 进项票关联。一张票覆盖一个周期的多张单（1:N），所以引用放在单这一侧。
ALTER TABLE stl_bill ADD COLUMN purchase_invoice_no VARCHAR(64) DEFAULT NULL
    COMMENT '所属进项票；空 = 尚未开票或无票供应商';
ALTER TABLE stl_bill ADD COLUMN invoice_status VARCHAR(16) NOT NULL DEFAULT 'PENDING_INVOICE'
    COMMENT 'PENDING_INVOICE/SUBMITTED/VERIFIED/REJECTED/NO_INVOICE。冗余一列是因为列表要按它筛';

CREATE INDEX idx_bill_business_mode ON stl_bill (business_mode);
CREATE INDEX idx_bill_invoice ON stl_bill (purchase_invoice_no);

-- ─────────────────────────────────────────────────────────────────────────────
-- 采购进项票登记
-- ─────────────────────────────────────────────────────────────────────────────
-- 这是**进项**票（供应商 → 平台），与 ops 契约里那三条销项票（开给消费者）不是一回事。
-- 契约里原本没有进项票，因为它按「平台模式」设计 —— 平台模式下平台不采购，自然没有进项。
--
-- 为什么必须有：自营模式下平台是销售主体，付给供应商的钱**没有发票就不能税前列支**。
-- 付得出去和能入账是两件事：对私转账银行不拦，但没有发票这笔支出在税上不存在，
-- 平台按全额确认收入却零成本。
CREATE TABLE IF NOT EXISTS stl_purchase_invoice
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    invoice_no VARCHAR(64) NOT NULL COMMENT '平台内部单号',
    entity_no VARCHAR(64) NOT NULL COMMENT '供应商',
    period VARCHAR(16) NOT NULL COMMENT '覆盖的结算周期，如 2026-08',
    invoice_code VARCHAR(32) DEFAULT NULL COMMENT '发票代码（部分电子发票无此项）',
    invoice_number VARCHAR(32) NOT NULL COMMENT '发票号码',
    invoice_type VARCHAR(16) NOT NULL DEFAULT 'GENERAL'
        COMMENT 'GENERAL 普票 / SPECIAL 专票。**只有专票能抵扣进项**，普票只解决企业所得税',
    title_name VARCHAR(128) NOT NULL COMMENT '开票方名称。三流一致比对用 —— 必须等于供应商主体名与结算账户户名',
    title_tax_no VARCHAR(32) DEFAULT NULL COMMENT '开票方税号',
    amount_minor BIGINT(20) NOT NULL COMMENT '价税合计（分）',
    tax_amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '税额（分）。专票才有抵扣意义',
    tax_rate INT(11) NOT NULL DEFAULT 0 COMMENT '税率，万分比',
    invoice_date BIGINT(20) DEFAULT NULL COMMENT '开票日期',
    image_url VARCHAR(512) DEFAULT NULL COMMENT '票面影像',
    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED'
        COMMENT 'SUBMITTED 待核验 / VERIFIED 已核验 / REJECTED 已驳回',
    verified_by VARCHAR(64) DEFAULT NULL COMMENT '核验人（运营 staffNo）',
    verified_at BIGINT(20) DEFAULT NULL,
    reject_reason VARCHAR(512) DEFAULT NULL COMMENT '驳回原因。**必填** —— 供应商得知道是抬头错了、金额不符还是影像看不清，否则只能反复试',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_invoice_no (invoice_no),
    -- 同一张发票不能提交两次。发票号码 + 代码是税务上的唯一标识，
    -- 靠它挡住「同一张票冲两个周期的账」这种最常见的重复报销
    UNIQUE KEY uk_invoice_number (invoice_code, invoice_number),
    KEY idx_purchase_invoice_entity (entity_no, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购进项票登记（自营）。供应商开给平台，平台据此列支成本';
