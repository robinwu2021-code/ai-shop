-- M6a.3 归因与门店主页（ADR-004 主获客路径）。
--
-- 两张表分工明确：
--   mkt_attribution     ── **当前**归属关系（每人一条，覆盖式更新）
--   mkt_attribution_log ── **每次判定**的留痕（append-only，可回放）
--
-- 为什么必须有第二张：归因决定费率档（R16），商家会为「这个客户算不算我带来的」争执。
-- 只存当前关系的话，争议发生时没有任何材料能还原「当时为什么判给了他」。

CREATE TABLE IF NOT EXISTS mkt_attribution
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no     VARCHAR(64) NOT NULL,
    merchant_no VARCHAR(64)  NULL COMMENT '店铺码归因命中的商家',
    inviter_no  VARCHAR(64)  NULL,
    channel     VARCHAR(64)  NULL,
    -- STORE_CODE / INVITER / CHANNEL —— 命中的来源，优先级从高到低
    source      VARCHAR(16) NOT NULL,
    expire_at   BIGINT      NOT NULL COMMENT '窗口期结束，默认 30 天（B1）',
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)  NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)  NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    -- 每人当前只有一条归属：切换是覆盖，历史在 log 里
    UNIQUE KEY uk_user (user_no),
    KEY idx_merchant (merchant_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '归因关系（当前）';

CREATE TABLE IF NOT EXISTS mkt_attribution_log
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no      VARCHAR(64) NOT NULL,
    merchant_no  VARCHAR(64)  NULL,
    inviter_no   VARCHAR(64)  NULL,
    channel      VARCHAR(64)  NULL,
    source       VARCHAR(16) NOT NULL,
    -- KEPT 保持原归属 / REPLACED 覆盖 / CREATED 首次
    decision     VARCHAR(16) NOT NULL,
    prev_source  VARCHAR(16)  NULL,
    prev_ref     VARCHAR(64)  NULL COMMENT '被覆盖前的归属对象',
    reason       VARCHAR(128) NULL COMMENT '判定依据，人可读',
    at           BIGINT      NOT NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    KEY idx_user_at (user_no, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '归因判定留痕（append-only，可回放）';

CREATE TABLE IF NOT EXISTS usr_store_favorite
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no     VARCHAR(64) NOT NULL,
    merchant_no VARCHAR(64) NOT NULL,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)  NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)  NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_merchant (user_no, merchant_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '收藏的店';

-- 店铺码：短码，印在包装袋/贴纸上（B-11.2.6）。
-- 与 merchant_no 分开是刻意的：merchant_no 会出现在各种日志与对账里，
-- 而店铺码是印在纸上给陌生人扫的，需要能单独作废重发。
ALTER TABLE usr_merchant ADD COLUMN store_code VARCHAR(32) NULL;
CREATE UNIQUE INDEX uk_store_code ON usr_merchant (store_code);
