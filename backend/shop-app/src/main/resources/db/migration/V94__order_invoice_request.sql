-- 开票申请（ADR-017 §3.4 条件 2 的落地第一步）。
--
-- 为什么现在做、而且是手工开票这一版：
--   归集路径要成立，四个必要条件缺一不可，其中第二条是「平台开票给消费者」。
--   而 C 端此前**零入口** —— 只有下单前一句「本商家无法开具发票」，
--   连申请的地方都没有。没有入口 = 没有履行途径，那是实质性缺失。
--
--   接票据系统（数电票/税控）是一个独立项目，等它就是无限期挂着。
--   但条件 2 要的是「平台承担开票义务并**实际履行**」，不要求自动化 ——
--   手工开票 + 可追溯的申请记录，法律关系上是成立的。
--   单量小的时候完全扛得住；扛不住那天，正是该接系统的信号。
--
-- 状态机（第二步接系统时在 ISSUED 之后延长，不改前面的）：
--   REQUESTED  已申请，等运营开
--   ISSUED     已开具并发出（运营回填票号与发出时间）
--   REJECTED   驳回（抬头/税号有误等），必须写原因
CREATE TABLE IF NOT EXISTS ord_invoice_request
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    request_no VARCHAR(32) NOT NULL COMMENT '申请单号',
    order_no VARCHAR(32) NOT NULL COMMENT '按**主单**申请，不按子单 —— 消费者眼里那是一次购买，票也该是一张',
    user_no VARCHAR(32) NOT NULL,
    title_type VARCHAR(16) NOT NULL DEFAULT 'PERSONAL' COMMENT 'PERSONAL 个人 / COMPANY 单位',
    title VARCHAR(128) NOT NULL COMMENT '抬头',
    tax_no VARCHAR(32) DEFAULT NULL COMMENT '税号。单位抬头必填，个人抬头无此项',
    email VARCHAR(128) NOT NULL COMMENT '收票邮箱。电子票只能发到这里，填错就是开了也收不到',
    amount_minor BIGINT(20) NOT NULL COMMENT '开票金额快照。**不实时读订单** —— 后续退款会改订单金额，而已开的票不会跟着变',
    status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    invoice_no VARCHAR(64) DEFAULT NULL COMMENT '票号，运营开完回填',
    issued_at BIGINT(20) DEFAULT NULL,
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因。不写原因的驳回等于让消费者再猜一遍',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '经办人 —— 手工开票必须留痕，否则事后查不到是谁开的',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ord_invoice_request (request_no),
    -- 一张订单只能申请一次：重复申请 = 重复开票 = 一笔交易两张票，
    -- 那是税务问题不是体验问题。改抬头走「驳回后重申请」，不靠再插一条
    UNIQUE KEY uk_ord_invoice_request_order (order_no, deleted),
    KEY idx_ord_invoice_request_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='开票申请（平台开给消费者，ADR-017 §3.4 条件 2）';
