-- S12 · 通道 × 市场：把一列 JSON 字符串升格成关系表
--
-- 今天 sys_pay_channel.markets 是一列 JSON 文本（如 ["CN"]），
-- 判「这个通道在不在这个市场」靠一段正则：去掉 []"\ 与空白、按逗号切开逐个 equals。
--
-- **那段正则里的反斜杠不是手滑。** 种子写的是 '[\"CN\"]' ——
-- MariaDB 把 \" 解成 "，存进去是 ["CN"]；H2 不处理这种转义，
-- 存进去带着反斜杠。同一句 SQL 在两个方言里存下不同的字节，
-- 于是判断逻辑必须先把方言差异抹平才能比。
-- 关系表里 market 是一个普通 VARCHAR 列 —— 两边一模一样。
--
-- 还有三件今天做不到的事：
--   · 没有任何东西保证列里的市场码真的存在。写错一个码，
--     那个通道在该市场静默消失，而看不出是配置错还是刻意关掉。
--   · 反向查不了：「TW 有哪些通道」要全表扫 + 在 Java 里逐行解析字符串。
--   · S14 的费率要按市场分档 —— JOIN 不了一段 JSON 文本。
--
-- **无行 = 不限市场**，沿用今天的语义。改成「无行 = 都不可用」会让
-- V288 那个刻意留空的 TEST 通道一夜消失，而它留空正是为了能在任何市场的链路上验证。

CREATE TABLE IF NOT EXISTS sys_pay_channel_market (
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pay_channel VARCHAR(32) NOT NULL COMMENT '通道码，对应 sys_pay_channel.pay_channel',
    market VARCHAR(8) NOT NULL COMMENT '市场码，对应 sys_market.market',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_market (tenant_no, pay_channel, market)
) COMMENT='通道在哪些市场可用。无行=不限市场';
-- 唯一键**不带 deleted** —— 这张表删行是物理删除（见 PayChannelMarketMapper）。
-- 带上 deleted 的话逻辑删除留下的墓碑会占着键位，
-- 运营第二次改同一个市场就撞重复键，而报错与「改市场」毫无关系。

-- 回填。
--
-- 匹配用的是**朴素的子串包含**，不是 token 切分 —— 因为这一句只跑一次，
-- 而跑的时候库里是什么我查过：生产三行（WECHAT/ALIPAY 都是 ["CN"]、TEST 是 NULL）。
-- 六个市场码都是两位且互不相同，等长且互异就不可能互为子串，
-- 所以「CN 命中 CNY」那类前缀重名在这份数据上不存在。
--
-- 子串匹配还顺带绕开了上面说的反斜杠问题：['、"、\] 怎么摆都不影响
-- 「这段文本里有没有 CN」。写 token 切分反而要在 SQL 里造一个
-- 可移植的反斜杠字面量 —— MariaDB 的 '\\' 是一个反斜杠、H2 的是两个。
INSERT IGNORE INTO sys_pay_channel_market
    (pay_channel, market, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT c.pay_channel, m.market, 'MAIN',
       '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0
FROM sys_pay_channel c
JOIN sys_market m ON c.markets LIKE CONCAT('%', m.market, '%')
WHERE c.markets IS NOT NULL
  AND c.markets <> ''
  AND c.deleted = 0
  AND m.deleted = 0;
