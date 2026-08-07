-- M3.3：落地变更单 C4 / C5 / C6（db-design.md §3）
--
-- ⚠️ 含列改名。当前开发期无生产数据，直接 RENAME；有数据时必须走
--    「加新列 → 双写 → 回填 → 停读旧列 → 删列」五步（db-design §7 已注明）。

-- C4：语义修正 —— 这是支付截止，不是订单过期（Q7，随前端 payDeadlineAt）
ALTER TABLE ord_order RENAME COLUMN expire_at TO pay_deadline_at;

-- C4：自提码/核销码/兑换码三态共用一个字段（Q7，随前端 verifyCode）
ALTER TABLE ord_sub_order RENAME COLUMN pickup_code TO verify_code;

-- C6：页面要显示自提点名称，不能只给号（A4 §2.3）
ALTER TABLE ord_sub_order ADD COLUMN pickup_name VARCHAR(128) NULL;

-- C4：优惠出资方拆开存 —— 平台券平台出、商家足额收款；商家券商家自己出、分账时扣减。
-- 合成一列的话，M7 分账时无法判断该扣谁的钱（db-design §3.4 / Q9）。
-- 即使 Q3 定为「券不进一期」也先建（恒 0）：加列是 DDL，重算历史分账是事故。
ALTER TABLE ord_sub_order ADD COLUMN discount_platform BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ord_sub_order ADD COLUMN discount_merchant BIGINT NOT NULL DEFAULT 0;

-- C5 / Q8：订单时间线（C-OC-03）。append-only，**不带 version/deleted** ——
-- 状态变更是既成事实，能改历史等于能伪造凭证。
CREATE TABLE IF NOT EXISTS ord_status_log
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_order_no  VARCHAR(64) NOT NULL COMMENT '时间线是子单粒度（Q6）',
    status        VARCHAR(16) NOT NULL,
    label         VARCHAR(64)  NULL COMMENT '展示文案',
    operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' COMMENT 'USER/MERCHANT/PLATFORM/SYSTEM',
    operator_no   VARCHAR(64)  NULL COMMENT '客服代客操作必须留痕',
    at            BIGINT      NOT NULL,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL,
    KEY idx_sub_order (sub_order_no, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单状态时间线（append-only）';

-- 索引：核销码必须**全局唯一**。核销台扫码时手上只有码、没有订单号，
-- 码不唯一意味着扫一次可能命中两单 —— 这是在货架前没法当场解决的事故。
CREATE UNIQUE INDEX uk_verify_code ON ord_sub_order (verify_code);

-- Q6 之后 C 端订单列表的主查询从主单挪到了子单
CREATE INDEX idx_sub_user_status ON ord_sub_order (user_no, status);

-- 自提点履约台「今日待核销」
CREATE INDEX idx_sub_pickup_status ON ord_sub_order (pickup_no, status);
