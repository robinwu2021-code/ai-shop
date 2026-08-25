-- 活动的新模型（P5）：活动 = 触发 × 优惠 × 排期 × 限量 × 受众 × 范围。
--
-- 老模型 `mkt_campaign` 用一个 `type` 枚举表达四类玩法（COUPON/FULL_CUT/FLASH/BUY_GIFT），
-- 于是「第二件半价」「满额送券」这种组合加不进去 —— 每加一种就要改一次算价。
-- 拆成正交的几段之后，四类玩法只是取值组合，新玩法是新组合而不是新枚举。
--
-- 与 pmt_coupon 一样：**只建表，不切换任何读写**。切换在 Port 那一步，可回退。
CREATE TABLE IF NOT EXISTS pmt_activity
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) DEFAULT NULL COMMENT '空 = 全部门店；有值 = 只在这家店生效',
    name VARCHAR(128) NOT NULL,
    goal VARCHAR(16) DEFAULT NULL COMMENT 'ACQUIRE 拉新 / WAKEUP 唤回 / CLEAR 清库存 / BASKET 提客单。只影响建的时候的入口与默认值',
    trigger_type VARCHAR(16) NOT NULL COMMENT 'NONE 无条件 / AMOUNT 订单满额 / QTY 买够件数 / GOODS 命中商品',
    trigger_amount_minor BIGINT(20) DEFAULT NULL COMMENT 'AMOUNT 时的门槛',
    trigger_qty INT(11) DEFAULT NULL COMMENT 'QTY 时的件数',
    benefit_type VARCHAR(16) NOT NULL COMMENT 'CUT 减金额 / PRICE 改单价 / GIFT 送商品 / COUPON 发券',
    benefit_amount_minor BIGINT(20) DEFAULT NULL COMMENT 'CUT 减多少 / PRICE 改成多少',
    benefit_qty INT(11) DEFAULT NULL COMMENT 'GIFT 送几件',
    benefit_ref VARCHAR(64) DEFAULT NULL COMMENT 'GIFT 送哪件商品 / COUPON 发哪张券（指向 pmt_coupon.coupon_no）',
    schedule_type VARCHAR(16) NOT NULL DEFAULT 'ONE_OFF' COMMENT 'ONE_OFF 短期 / ALWAYS_ON 长期 / RECURRING 周期',
    start_at BIGINT(20) DEFAULT NULL COMMENT 'ALWAYS_ON 可为空',
    end_at BIGINT(20) DEFAULT NULL,
    schedule_rule VARCHAR(255) DEFAULT NULL COMMENT 'RECURRING：JSON {weekdays:[3],from:"08:00",to:"20:00"}，按市场时区',
    quota INT(11) DEFAULT NULL COMMENT '限量。PRICE 与 GIFT 必填，ALWAYS_ON 一律必填 —— 没有结束时间又没上限就是永久敞口',
    quota_used INT(11) NOT NULL DEFAULT 0 COMMENT '并发计数器，不是缓存：防超发靠一条带条件的 UPDATE',
    budget_minor BIGINT(20) DEFAULT NULL COMMENT '预算上限（分）。与 quota 至少填一个',
    budget_used_minor BIGINT(20) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / RUNNING / PAUSED / ENDED',
    ended_reason VARCHAR(16) DEFAULT NULL COMMENT 'EXPIRED 到期 / QUOTA 到量 / BUDGET 预算用尽 / MANUAL 手动。商家问「怎么停了」要有答案',
    archived_at DATETIME DEFAULT NULL COMMENT '归档：从列表消失，数据保留。与 status 正交',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_activity_no (activity_no),
    KEY idx_pmt_activity_live (entity_no, status, start_at, end_at),
    KEY idx_pmt_activity_store (entity_no, store_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='活动：触发条件 × 优惠形式 × 排期 × 限量';

-- 受众：**一行都没有 = 对所有人生效**。
-- 这条默认值是刻意的：老活动没有受众概念，迁过来之后行为必须逐分不变。
CREATE TABLE IF NOT EXISTS pmt_activity_audience
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    audience_type VARCHAR(16) NOT NULL COMMENT 'TAG 标签号 / LEVEL 会员层 / SOURCE 来源 / SEGMENT 人群 / NON_MEMBER 非本店会员',
    audience_value VARCHAR(64) NOT NULL COMMENT '标签号 / 层 / 来源 / 人群号。存号不存文本 —— 标签改名不该动这里',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_audience (tenant_no, activity_no, audience_type, audience_value),
    KEY idx_pmt_audience_activity (activity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='活动受众：一行都没有 = 对所有人生效';

-- 作用范围。**用表不用 TEXT**：建活动时要按商品号反查「这件商品已经在哪些活动里」
-- （冲突提示），塞在 goods_nos TEXT 里的话这个问题只能全表扫。
CREATE TABLE IF NOT EXISTS pmt_activity_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GOODS' COMMENT 'GOODS 指定商品 / CATEGORY 指定类目 / ALL 全店',
    ref_no VARCHAR(64) NOT NULL COMMENT '商品号或类目号；ALL 时填 *',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_activity_goods (tenant_no, activity_no, scope_type, ref_no),
    KEY idx_pmt_goods_ref (entity_no, scope_type, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='活动作用范围。用表不用 TEXT：要反查「这个商品在哪些活动里」';
