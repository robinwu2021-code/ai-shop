-- 店铺码印刷量登记（append-only 台账）。
--
-- **为什么要运营手工录**：印了多少张贴纸是**线下事实**，系统不可能自动知道。
-- 曾考虑按「导出次数 × 每次张数」估算 —— 否掉了：BD 导出一次可能印 0 张，
-- 也可能印 500 张，估出来的是一个看起来很精确的编造值，而看板上没人能分辨。
--
-- **一次登记一行，不做累计列**：累计值由行相加得出。存一个 total 列就要维护它，
-- 而「改一次数量」在有台账时是补一行（正负都行），在有 total 列时是就地改 ——
-- 后者把「当初到底印了多少」永久抹掉，而那正是对账要问的。
--
-- ⚠️ 撞号风险：并行会话同一目录，H2 测试不跑 Flyway，撞号只在下次真库启动才暴露。
CREATE TABLE IF NOT EXISTS mch_store_qrcode_print
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    print_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体。店铺码一主体一码，印刷量因此也挂主体',
    -- 有符号：印多了要冲减就补一行负数，而不是回头改上一行（改了就查不到当初印了多少）
    qty INT(11) NOT NULL COMMENT '本次印量，**有符号** —— 冲减补负数行，不改历史行',
    size VARCHAR(32) DEFAULT NULL COMMENT '贴纸尺寸，如 10x10cm。属于这一次印刷，不是门店属性',
    remark VARCHAR(255) DEFAULT NULL,
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '谁登记的 —— 这是一笔会进成本对账的数',
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_qrcode_print_no (print_no),
    KEY idx_store_qrcode_print_entity (entity_no,at)
) COMMENT='店铺码印刷量登记台账（线下事实，运营录入）';
