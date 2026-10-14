-- 第 5 步 · 5.4：行级退款的数据底座（TDD-订单状态机 §2C AC19–AC22）
--
-- 1. ord_item：分摊后行实付快照 + 已退件数 / 金额。存量行的快照**不在这里回填** ——
--    「零头补到某一行」要 UPDATE 子查询引用同表，MySQL 报 1093、H2 语法又不同；
--    读时由 LineShares 用同一算法现算（存量单只需撑过售后期）。
-- 2. ord_sub_order：完成时刻（售后期起算点）、已退总额、退款进度（与订单状态正交）。
-- 3. ord_after_sale_item：一张售后单退的是哪几行、各几件、各多少钱。
--
-- 加列 + 建表 + 两条无自引用的回填，向后兼容，可重复执行。不写 ENGINE/CHARSET（见 prod-mysql97）。

ALTER TABLE ord_item
    ADD COLUMN paid_share_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '分摊后的本行实付（含优惠与积分抵扣分摊，不含运费），下单时落快照；存量为 0 读时现算',
    ADD COLUMN refunded_qty     INT(11)    NOT NULL DEFAULT 0 COMMENT '已退件数',
    ADD COLUMN refunded_minor   BIGINT(20) NOT NULL DEFAULT 0 COMMENT '已退金额';

ALTER TABLE ord_sub_order
    ADD COLUMN completed_at    BIGINT(20) DEFAULT NULL COMMENT '完成时刻（毫秒），售后期起算点',
    ADD COLUMN refunded_minor  BIGINT(20) NOT NULL DEFAULT 0 COMMENT '已退总额（含运费）',
    ADD COLUMN refund_progress VARCHAR(8) NOT NULL DEFAULT 'NONE' COMMENT '退款进度：NONE / PARTIAL / FULL，与订单状态正交';

CREATE TABLE IF NOT EXISTS ord_after_sale_item (
    id            BIGINT(20)  NOT NULL AUTO_INCREMENT,
    after_sale_no VARCHAR(40) NOT NULL COMMENT '售后单号',
    sub_order_no  VARCHAR(40) NOT NULL COMMENT '所属订单',
    item_id       BIGINT(20)  NOT NULL COMMENT '订单行（ord_item.id）',
    qty           INT(11)     NOT NULL COMMENT '本次退的件数',
    refund_minor  BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '本行本次退款额（商家核定 / 协商后按比例分回）',
    tenant_no     VARCHAR(32) DEFAULT NULL,
    created_at    DATETIME    DEFAULT NULL,
    created_by    VARCHAR(64) DEFAULT NULL,
    updated_at    DATETIME    DEFAULT NULL,
    updated_by    VARCHAR(64) DEFAULT NULL,
    version       BIGINT(20)  NOT NULL DEFAULT 0,
    deleted       TINYINT(4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ord_after_sale_item (after_sale_no, item_id),
    KEY idx_ord_after_sale_item_sub (sub_order_no)
) COMMENT='售后行：一张售后单退哪几行、各几件、各多少钱';

-- 存量完成时刻：取状态流水里最后一条 COMPLETED
UPDATE ord_sub_order s
SET completed_at = (SELECT MAX(l.at) FROM ord_status_log l
                    WHERE l.sub_order_no = s.sub_order_no AND l.status = 'COMPLETED')
WHERE s.completed_at IS NULL
  AND s.status IN ('COMPLETED', 'REFUNDED');

-- 存量已退款子单：存量只有整单退，标 FULL；已退额取其售后实退额之和（取不到按实付）
UPDATE ord_sub_order s
SET refund_progress = 'FULL',
    refunded_minor  = COALESCE((SELECT SUM(COALESCE(a.approved_minor, a.refund_minor)) FROM ord_after_sale a
                                WHERE a.sub_order_no = s.sub_order_no AND a.status = 'REFUNDED'), s.pay_amount)
WHERE s.status = 'REFUNDED'
  AND s.refund_progress = 'NONE';
