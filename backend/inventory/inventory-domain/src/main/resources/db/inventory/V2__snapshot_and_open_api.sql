-- 日快照与 Open API 凭证。
--
-- 两张表放同一条迁移：它们都是**「本域自己长出来的能力」**，不改任何既有列，
-- 上线当天零行为变化（快照表空着、凭证表空着，谁都不读）。

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 日快照
-- ─────────────────────────────────────────────────────────────────────────────
-- 报表在此之前直接聚合流水，量小的时候完全够用。这张表解决的是**区间查询的劣化**：
-- 「今年每个月的进销存」在流水上是一次全量扫描，而在快照上是十二行。
--
-- **按天不按小时**：社区店的经营决策粒度是天。按小时的表大 24 倍，
-- 而目前没有一个问题需要它。
--
-- **它是派生数据，不是真源**：删光重跑一遍就回来了。所以不设 updated_by，
-- 也不进「只追加」那一档 —— 重跑是它的正常工作方式。
CREATE TABLE IF NOT EXISTS inv_daily_snapshot
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id          VARCHAR(32) NOT NULL,
    stat_date         DATE        NOT NULL COMMENT '统计日。按业务发生时间归期，不按落库时间',
    item_id           VARCHAR(32) NOT NULL,
    location_id       VARCHAR(32) NOT NULL,
    opening_qty       INT         NOT NULL DEFAULT 0 COMMENT '期初 = 前一天的期末',
    inbound_qty       INT         NOT NULL DEFAULT 0 COMMENT '当日入库合计',
    outbound_qty      INT         NOT NULL DEFAULT 0 COMMENT '当日出库合计（正数）',
    sold_qty          INT         NOT NULL DEFAULT 0 COMMENT '其中销售出库的量。动销榜取它',
    sold_cost_minor   BIGINT      NOT NULL DEFAULT 0 COMMENT '销货成本。**不是销售额** —— 售价在销售域',
    closing_qty       INT         NOT NULL DEFAULT 0 COMMENT '期末结存',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(64) DEFAULT NULL COMMENT '跑批写 SYSTEM',
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by        VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    -- 一天一物料一库位一行。**重跑靠先删后插**，不靠 REPLACE ——
    -- REPLACE 在 MySQL 里是删了再插，自增主键会跳号，而这张表的 id 没人引用，
    -- 但「跳号」会让人以为丢了数据
    UNIQUE KEY uk_snapshot (owner_id, stat_date, item_id, location_id),
    KEY idx_snapshot_range (owner_id, location_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='日快照：派生数据，删光重跑即可';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Open API 凭证
-- ─────────────────────────────────────────────────────────────────────────────
-- **不复用 B 端 JWT**：那是给人用的 —— 有效期短、绑设备、要短信验证码。
-- 服务端对接拿不到，也不该拿到。
--
-- app_secret **只存哈希**（与运营端密码同一套 bcrypt）。存明文的话，
-- 一次数据库导出就等于把所有对接方的凭证交出去了，而对方不会知道。
CREATE TABLE IF NOT EXISTS inv_open_credential
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    credential_id   VARCHAR(32)  NOT NULL,
    owner_id        VARCHAR(32)  NOT NULL COMMENT '这把钥匙只能看这一个业主的货',
    app_key         VARCHAR(64)  NOT NULL,
    app_secret_hash VARCHAR(128) NOT NULL COMMENT 'bcrypt。**不存明文**',
    name            VARCHAR(64)  DEFAULT NULL COMMENT '给人看的：这把钥匙给了谁',
    scopes          VARCHAR(255) NOT NULL DEFAULT 'read' COMMENT '逗号分隔：read / stock:sync',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED。**吊销不删行** —— 谁在什么时候被吊销要查得到',
    expires_at      DATETIME     DEFAULT NULL COMMENT '空 = 不过期',
    last_used_at    DATETIME     DEFAULT NULL COMMENT '发现「这把钥匙半年没人用了」的唯一依据',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64)  DEFAULT NULL,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_credential (credential_id),
    -- app_key 全局唯一：它是查找键，请求头里只有它，没有 owner
    UNIQUE KEY uk_app_key (app_key),
    KEY idx_credential_owner (owner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='Open API 凭证：服务端到服务端，不复用给人用的令牌';
