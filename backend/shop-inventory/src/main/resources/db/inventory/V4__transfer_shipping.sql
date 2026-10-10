-- 调拨发货信息。**在这之前 `ship(no)` 只收单号** —— 发给谁在运、运单号多少，
-- 一个都不存。库存页「在途 N」点进去看得到那张单，而收货方只能凭记忆核对。
--
-- 承运方**复用主库的 `ful_carrier`**（履约域，2026-08-30 时 3 行）：
-- 承运方是全平台共用的事实，进销存再造一张只会让两处名字对不上。
-- 但进销存是独立库、独立数据源，**跨库不能外键** —— 只能存编号 + 冗余名字。
--
-- 冗余名字不是偷懒：**单据要能自证**。承运方三个月后改了名，
-- 这张历史调拨单该显示当时那个名字，而不是跟着变。
-- 方案见 docs/technical/design/进销存-供应商与发货要素.md §三②
--
-- 排序规则与 V3 同：MySQL 也认的那个，理由见 V3 的注释。

ALTER TABLE inv_transfer_order
    ADD COLUMN carrier_no VARCHAR(32) DEFAULT NULL COMMENT '承运方编号，指向主库 ful_carrier；空 = 自己送或没记' AFTER shipped_at;

ALTER TABLE inv_transfer_order
    ADD COLUMN carrier_name VARCHAR(64) DEFAULT NULL COMMENT '发货当时的承运方名字快照 —— 对方改名后历史单仍显示当时那个' AFTER carrier_no;

-- 运单号是**值不是实体**，所以它是输入框不是选择器（见方案 §五）。
ALTER TABLE inv_transfer_order
    ADD COLUMN tracking_no VARCHAR(64) DEFAULT NULL COMMENT '运单号；手输 —— 它是一串码，没有可选列表' AFTER carrier_name;

-- 按承运方查「这个月哪家发得多」；带 owner_id 是因为进销存靠显式条件隔离，
-- 不走平台 DataScope（见 V3 注释）
CREATE INDEX idx_trf_carrier ON inv_transfer_order (owner_id, carrier_no);
