-- 待生效积分转正：补上让整条积分链跑起来的那一环。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 断在哪
-- ─────────────────────────────────────────────────────────────────────────────
-- grantOnPay 发分时**从不写 available_at**（全库零写入点，已 grep 确认），
-- 而 grantPending 只加 pending_balance、从不加 balance。
-- 注释里写着「转正由独立任务负责」—— 那个任务不存在。
--
-- 后果链：available_at 恒 NULL → 没有任何任务扫得到 → balance 恒 0
--        → maxUsablePoints 算出 0 → **抵扣永远抵不了**。
-- 用户看得见分在涨（pending_balance），却一分也花不出去；
-- account() 的 nextActivate 也恒返回 null，页面连「何时生效」都显示不了。
--
-- 全链路没有任何一处报错。这是「闸门写好了但数据源没接」的第三例。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么要一个显式的 activated 标记，而不是靠 available_at <= now 判
-- ─────────────────────────────────────────────────────────────────────────────
-- 只按时间判的话，任务每天扫到的都是**同一批已经转过的行** ——
-- 第二次转正会把同一笔分再加一遍进 balance，而 pending 早已扣完，
-- 于是 `pending_balance >= points` 的守卫把它拦下、返回 0 行、任务当成「没事发生」。
-- 账面上不报错，但也永远不知道自己在空转。
--
-- 标记一次性写死，任务只扫「到点且未转正」的行 —— 幂等靠它，不靠余额守卫兜底。

ALTER TABLE pts_user_ledger ADD COLUMN activated_at BIGINT(20) DEFAULT NULL
    COMMENT '仅 EARN：转正时间（pending → balance 那一刻）。
             NULL = 尚未转正。**幂等键**：转正任务只扫 available_at <= now 且它为 NULL 的行';

-- 扫描索引：任务按 (biz_type, activated_at, available_at) 找待转正的行。
-- 已有的 idx_pts_activate(available_at, biz_type) 选择性不够 ——
-- 转正过的行会永远留在索引里，扫描量随时间只增不减。
CREATE INDEX idx_pts_pending_activate ON pts_user_ledger (activated_at, available_at, biz_type);
