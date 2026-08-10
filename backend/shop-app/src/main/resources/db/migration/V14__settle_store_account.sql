-- 结算分店：把「哪家店挣的」与「打给哪个账户」从 entity_no 里拆出来。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 三个维度各归各
-- ─────────────────────────────────────────────────────────────────────────────
--   store_no        统计维度 —— 这笔钱是**哪家店**挣的（系统定：订单落在哪家店）
--   pay_merchant_no 结算维度 —— 这笔钱打给**哪个账户**（商家定：给门店配收款号）
--   entity_no       合规维度 —— 谁开票、纳税、担责，费率按谁算（营业执照定）
--
-- 拆开之后「分开结算 / 合并结算」是配置的结果，不需要开关：
-- 两家店配同一个收款号就是合并，配不同的就是分开。
-- 存一个 settleMode 枚举反而会与 pay_merchant_no 打架 —— 配置说分、开关说合，
-- 听谁的都是错的。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么两个字段都是**快照**，不是每次 join 回去算
-- ─────────────────────────────────────────────────────────────────────────────
-- 与 commission_rate 落库同一个理由：商家随时可以改门店的收款号
-- （mch_store.payment_changed_at 已经在记这个变更）。
-- 若打款时才去查「这家店现在用哪个号」，**改号会把还没打的历史流水一起挪到新账户**，
-- 钱已经进了旧账户，账却说打给新账户，对不上。
--
-- 退款同理，而且更狠：退款必须从当初收款的那个账户出。靠的就是这里的快照 ——
-- 不是「现在这家店用哪个号」。否则商家上月改了号、这月退上月的单，
-- 钱从新账户扣，两个账户各错一笔且方向相反。
ALTER TABLE stl_bill ADD COLUMN store_no VARCHAR(64) DEFAULT NULL
    COMMENT '这笔钱是哪家店挣的（ord_sub_order.store_no 快照）。空 = 存量主体级流水';

-- 可空：存量流水都是主体级的，不能强制填。
-- 读侧按「空 = 用主体的默认收款号」解释 —— 与 mch_store.pay_merchant_no 已有的口径一致
ALTER TABLE stl_bill ADD COLUMN pay_merchant_no VARCHAR(64) DEFAULT NULL
    COMMENT '这笔钱打给哪个收款商户号（生成时快照）。空 = 用主体的默认收款号';

-- 打款单按 (收款号, 周期) 归组，门店报表按 store_no 聚合 —— 两条读路径各一个索引
CREATE INDEX idx_bill_pay_merchant ON stl_bill (pay_merchant_no);
CREATE INDEX idx_bill_store ON stl_bill (store_no);

-- ─────────────────────────────────────────────────────────────────────────────
-- 进件按门店：不放开这个，「分开结算」在数据层就做不到
-- ─────────────────────────────────────────────────────────────────────────────
-- 原唯一键是 uk_mch_payment_entity_channel(entity_no, pay_channel) ——
-- **一个主体每个通道只有一个收款号**。于是 mch_store.pay_merchant_no 这个字段
-- 从一开始就只有一个候选值可选：门店能「配」收款号，但全主体只有一个号能配。
-- 「两家店各收各的钱」在 schema 层就是不可能的。
--
-- 微信侧的对应关系是清楚的：一个商户号绑一个结算账户，要两个账户就得进件两次，
-- 拿到两个特约商户号。所以这里加 store_no —— 一家店一次进件。
--
-- store_no 用 '' 而不是 NULL 表示「主体级」：MySQL 的唯一索引不约束 NULL，
-- 用 NULL 的话同一主体能插进无数条主体级记录，而那正是这个键要挡的东西。
ALTER TABLE mch_payment_merchant ADD COLUMN store_no VARCHAR(64) NOT NULL DEFAULT ''
    COMMENT '这次进件是为哪家门店做的。**空串 = 主体级默认号**（单店与存量都是它）';

ALTER TABLE mch_payment_merchant DROP INDEX uk_mp_entity_channel;
ALTER TABLE mch_payment_merchant ADD UNIQUE KEY uk_mp_entity_channel_store (entity_no,pay_channel,store_no);
