-- ① 主体挂市场（国际化的地基）
--
-- 通道侧的按市场筛选**早就实现了**（PayChannelMasterService.enabled(market)
-- 逐条比 sys_pay_channel.markets），运营端也能改那个字段。
-- 缺的一直只是**「这家商家在哪个市场」这个输入** ——
-- 两处取可用通道都传的是 null，于是一律按默认市场（CN）算，
-- 台湾商家与大陆商家看到同一份通道列表。
--
-- 挂在主体而不是门店：一个主体在两个市场经营要开两个主体，
-- 这与「收款进件按主体」是同一条口径。
ALTER TABLE mch_entity
    ADD COLUMN market VARCHAR(8) NOT NULL DEFAULT 'CN'
    COMMENT '经营市场。决定可选支付渠道、结算币种、账期时区';

-- ② 测试渠道
--
-- **它是一条真的通道记录，不是开关**：进件、下单、回调、结算、对账、提现
-- 整条链都按真通道走，只有「与银行之间那一段」是假的。
-- 目的是在真通道凭证到位之前，把上下游全部跑通一遍 ——
-- 而那正是最容易在接真通道那天才发现问题的地方。
--
-- ⚠️ **将来要删。**删的时候三件事一起做：
--   1. 这条记录（DELETE FROM sys_pay_channel WHERE pay_channel='TEST'）
--   2. TestPayGateway 与它的 ChannelClient 桩
--   3. 检查有没有商家还签着它（mch_payment_merchant）—— 有的话先迁走
-- 留一条 TODO 在 TestPayGateway 的类注释里，那儿比这里更容易被看到。
--
-- ⚠️ 时间戳有两个坑，都撞过：
--   ① 不用 UNIX_TIMESTAMP() —— 那是 MySQL/MariaDB 函数，测试库的 H2 跑不了；
--   ② **这张表的 created_at 是 TIMESTAMP，不是别处那种毫秒 BIGINT**。
--      写毫秒数字会报「Data conversion error」。
-- 两次的症状一模一样：整个 Spring 上下文起不来，
-- 而报错指向一个毫不相干的 bizAuthController —— 中间隔着
-- 「schema 脚本失败 → 数据源初始化失败 → 依赖它的 bean 建不出来」三层。
-- **同一张表的列类型不能靠印象**，跟着已有种子的写法走。
--
-- markets 留空 = **所有市场都可用**（见 marketAllowed：空表示不限）。
-- 这是刻意的：测试渠道要能在任何市场的链路上验证。
--
-- enabled = 0：默认关着。开它是运营的一次明确动作，
-- 而不是「上线就有一条假通道在列表里」。
-- ⚠️ **显式给 id**。已有种子（V2）用 INSERT 带 id 插了 WECHAT=1 / ALIPAY=2，
-- 而显式插入不会推进自增序列 —— 不带 id 的话这条会分到 id=1，主键冲突。
-- 症状是整个测试上下文起不来，报错指向一个毫不相干的 bizAuthController，
-- 而真因是这一行。（生产库的序列是真实推进过的，所以只在测试库炸 ——
-- 两边行为不同，更要在这里写死。）
INSERT INTO sys_pay_channel
    (id, pay_channel, name, enabled, supports_subsidy, supports_split, supports_payout,
     pay_methods, markets, currency, settle_cycle, max_partial_refunds,
     refund_interval_seconds, max_split_rate, tenant_no,
     created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (3, 'TEST', '测试渠道', 0, 1, 1, 1,
     'TEST_PAY', NULL, 'CNY', 'T1', 20,
     0, 3000, 'MAIN',
     '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0);
