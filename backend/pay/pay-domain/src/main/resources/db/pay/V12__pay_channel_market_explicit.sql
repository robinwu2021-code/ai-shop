-- 支付服务 v4 · 迁移 12（W2 渠道层补完 S8 · TDD-支付服务-W2渠道层补完；复核 §3.8 编号表 V12，补 I2 偏差 1、2）。
--
-- sys_pay_channel_market 的语义从「无行 = 不限市场」改为「无行 = 不可用」（v4 模型、PRD 多市场）。
-- 旧语义下，一个没配市场的新渠道（蓝新 / LINE Pay / PayBy）会在所有市场出现，包括大陆 ——
-- 收银台列出一个点了必失败的方式；而它一旦开通凭据，就是在错误的地区收错币种的钱。
--
-- 切换本身不改表结构，只改数据与读法（PayChannelMasterServiceImpl#enabled）。为了「切换那一刻谁都不消失」：
--   1) TEST 补显式行，每个市场一行 —— V288 刻意留空，就是为了能在任何市场的链路上验证；
--   2) 其余一行都没有的通道，把存量的「不限」原样搬成全部市场的显式行（生产查过只有 TEST 一条，见 V295 注释；
--      这一句兜的是没查过的环境）；
--   3) 新渠道 NEWEBPAY / LINEPAY / PAYBY 的渠道行（默认停用：没有处理器、没有凭据，开了也路由不到 —— 等 I8）；
--   4) 它们各自的市场行（TW / TW / AE）。
-- 新渠道的 sys_pay_channel.markets 旧列不写：V295 起那一列不再被读写。

-- 1) TEST：每个市场一行（与 sys_market 的六个种子市场一一对应；以后加市场要给 TEST 补行，否则测试渠道在新市场不可用）
INSERT IGNORE INTO sys_pay_channel_market
    (pay_channel, market, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('TEST', 'CN', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('TEST', 'TW', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('TEST', 'HK', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('TEST', 'SG', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('TEST', 'AE', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('TEST', 'SA', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0);

-- 2) 其余没有任何市场行的通道：存量的「不限市场」搬成全部市场的显式行
INSERT IGNORE INTO sys_pay_channel_market
    (pay_channel, market, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT c.pay_channel, m.market, 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0
  FROM sys_pay_channel c, sys_market m
 WHERE c.deleted = 0
   AND m.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_pay_channel_market x WHERE x.pay_channel = c.pay_channel);

-- 3) 新渠道行，默认停用（I8 接入时由运营打开）
INSERT IGNORE INTO sys_pay_channel
    (pay_channel, name, enabled, supports_subsidy, supports_split, supports_payout, pay_methods, currency,
     tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('NEWEBPAY', '蓝新金流', 0, 0, 0, 0, '["CARD_ONLINE","ATM","CVS"]', 'TWD',
     'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('LINEPAY', 'LINE Pay', 0, 0, 0, 0, '["LINE_PAY"]', 'TWD',
     'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('PAYBY', 'PayBy', 0, 0, 0, 1, '["WALLET","CARD_ONLINE"]', 'AED',
     'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0);

-- 4) 新渠道的市场行：蓝新与 LINE Pay 只在台湾，PayBy 只在阿联酋
INSERT IGNORE INTO sys_pay_channel_market
    (pay_channel, market, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('NEWEBPAY', 'TW', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('LINEPAY', 'TW', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0),
    ('PAYBY', 'AE', 'MAIN', '2026-10-26 00:00:00', 'SYSTEM', '2026-10-26 00:00:00', 'SYSTEM', 0, 0);
