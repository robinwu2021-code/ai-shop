-- 门店级库存。
--
-- 为什么需要：`prd_sku` 的唯一键是 (entity_no, sku_no, market)，**没有门店维度**。
-- 一家主体开第二家店之后，文三路店卖光了古墩路店也显示无货；反过来顾客在 A 店下单，
-- 库存从「全公司总量」里扣，而货其实在 B 店 —— 自提场景下这是直接的履约事故：
-- 顾客到店取不到货。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么不直接给 prd_sku 加 store_no
-- ─────────────────────────────────────────────────────────────────────────────
-- 那要求一次性把所有存量库存迁到「默认店」名下，而现在**所有真实商家都是单店**。
-- 为一个还没人用的功能，让全量商家的库存做一次不可回退的迁移，风险与收益不成比例。
--
-- 这张表是**可选的覆盖层**：
--   · 某个 SKU 一条店级库存行都没有 → 走 prd_sku.stock，行为与今天逐字相同
--   · 一旦有了任意一条 → 该 SKU 整体转为店级管理，**没有行的店视为 0**
--
-- 最后半句是关键。若改成「没有行就回退到总量」，商家给 A 店设了 10 件之后，
-- B 店会变成「无限库存」—— 那比不分店更危险。转换按 SKU 粒度、由商家显式触发
-- （在某家店设一次库存），语义可解释：「你给这个商品设了分店库存，那就得每家店都设」。
CREATE TABLE IF NOT EXISTS prd_store_stock
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL COMMENT '门店业务键（mch_store.store_no）',
    sku_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '冗余主体号：按商家查全部门店库存时免 join',
    stock INT(11) NOT NULL DEFAULT 0 COMMENT '这家店的总量',
    locked_stock INT(11) NOT NULL DEFAULT 0 COMMENT '这家店已锁定（下单未支付）的量',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一店一 SKU 一行。并发下靠这个唯一键 + 条件更新防超卖，与 prd_sku 同一手法
    UNIQUE KEY uk_store_sku (store_no,sku_no),
    KEY idx_sku (sku_no),
    KEY idx_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='门店级库存：有行则按店算，一条都没有则回退主体总量';

-- 锁定记录加门店：释放与确认要知道减回哪家店的数。
-- 可空 —— 存量锁定行都是主体级的，不能强制填
ALTER TABLE prd_stock_lock ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '锁的是哪家店的库存；空 = 主体级（存量或单店）';
