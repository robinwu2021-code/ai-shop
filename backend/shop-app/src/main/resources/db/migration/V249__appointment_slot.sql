-- 预约时段：把「买家自己填一个时间戳」换成「从商家开出的名额里挑一个」。
--
-- 现状是 ord_sub_order.appointment_at 收一个**任意的未来时间**（V1 baseline 就有），
-- 校验只有「非空且不在过去」。于是同一个上门师傅可以被约到十个人手里 ——
-- 而系统里没有任何地方看得出来，直到当天有九个人白等。
--
-- ⚠️ **不是把旧字段换掉**：appointment_at 继续写（商家的待服务列表按它排），
-- 只是多了一个来源。没开时段的商家照旧按老路走 —— 与门店渠道
-- 「一行都没有 = 还没迁过来，按旧口径放行」同一条兼容规矩。

-- ── 1. 时段与名额 ──
--
-- **归属是门店不是商品**：能同时上几单取决于这家店有几个师傅，
-- 与卖的是保洁还是维修无关。挂到商品上的话，两个商品各配 3 个名额，
-- 同一个师傅会被这两条各约 3 次。
--
-- capacity 与 booked **分两列**，不存「剩余」一个数：
-- 剩余是派生的，而「原本开了几个」在排期复盘时要用 ——
-- 只存剩余的话，一个满掉的时段和一个从没开过的时段长得一模一样。
CREATE TABLE IF NOT EXISTS mch_appointment_slot
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    slot_no    VARCHAR(64) NOT NULL,
    entity_no  VARCHAR(64) NOT NULL,
    store_no   VARCHAR(64) NOT NULL,
    start_at   BIGINT      NOT NULL COMMENT '时段开始（毫秒时间戳）',
    end_at     BIGINT      NOT NULL COMMENT '时段结束',
    capacity   INT         NOT NULL DEFAULT 1 COMMENT '这个时段能接几单',
    booked     INT         NOT NULL DEFAULT 0 COMMENT '已占用。**只能靠带条件的 UPDATE 改**',
    status     VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 可约 / CLOSED 停约',
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)          DEFAULT NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)          DEFAULT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_slot_no (slot_no),
    KEY idx_slot_store_time (tenant_no, store_no, start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='门店预约时段与名额';

-- ── 2. 子单记住占的是哪个名额 ──
--
-- 两列缺一不可：
--   slot_no          —— 取消时知道该还给谁；也是出纠纷时「他到底约的哪一档」的凭据
--   released_at      —— **释放的幂等标记**。取消可能被重放（超时关闭 + 用户手动取消
--                       同时到达），没有这个标记就会把名额还两次，
--                       于是 booked 减成负数、一个时段被卖出比 capacity 更多的单
--
-- ⚠️ 释放要写成「先条件 UPDATE 打标记，成功了才去减 booked」——
-- 反过来（先减再打标记）在并发重放下仍然会多减一次。
ALTER TABLE ord_sub_order ADD COLUMN appointment_slot_no VARCHAR(64) DEFAULT NULL
    COMMENT '占用的预约时段；空 = 商家没开时段，走 appointment_at 的旧路';
ALTER TABLE ord_sub_order ADD COLUMN appointment_released_at BIGINT DEFAULT NULL
    COMMENT '名额释放时刻。**幂等标记** —— 非空即已还过，不许再减 booked';
