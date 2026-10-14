-- 测试库的补充种子。**只在测试里执行**，不进任何迁移。
--
-- 为什么需要它：S4 让下单走网关之后，通道解析取的是
-- 「运营开着的 ∩ 网关有实现的」。而测试库里：
--   · WECHAT / ALIPAY 是 enabled=1，但它们的网关要凭证，没装配；
--   · TEST 的网关总是装配，而它 enabled=0（默认关是刻意的，
--     不能让一条假通道出现在生产的真实渠道列表里）。
-- 两边一交，候选集是空的 —— 30 个走支付链路的场景测试全红。
--
-- 所以测试库把 TEST 开起来。**这不是把生产的默认值改掉**，
-- 而是给测试环境配一个「有实现的通道」——
-- 正如生产环境将来会配真通道的凭证。
UPDATE sys_pay_channel SET enabled = 1 WHERE pay_channel = 'TEST';

-- ── V295 的回填结果（通道 × 市场）──────────────────────────────────
--
-- **为什么要在这里手写一遍**：schema-test.sql 的生成器<b>刻意丢掉
-- INSERT … SELECT</b>（它的注释写着「那是数据回填，不是种子」）。
-- 于是 V295 的回填在 H2 里根本不执行，sys_pay_channel_market 是空的。
--
-- 而空表恰好命中「无行 = 不限市场」这条语义 —— 所有断言照样绿，
-- <b>而绿的原因是「谁都不受限」，不是「回填对了」</b>。
-- 这种假绿最难发现：判据本身没测到要测的那件事。
--
-- 下面这两行是**生产库当场查出来的回填结果**（2026-09-02）：
--   WECHAT ["CN"] · ALIPAY ["CN"] · TEST NULL（不限市场，V288 刻意留空）
-- 回填逻辑一改，这里对不上就该有用例变红。
INSERT INTO sys_pay_channel_market
    (pay_channel, market, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('WECHAT', 'CN', 'MAIN', '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0),
    ('ALIPAY', 'CN', 'MAIN', '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0);
