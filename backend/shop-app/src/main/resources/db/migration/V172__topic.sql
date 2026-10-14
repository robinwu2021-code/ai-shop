-- 主题分类（商品域-优化总方案 批 E）。
--
-- 「早餐必备」「宵夜档」「开学季」这类**陈列**，与类目正交：一件豆浆同时属于
-- 类目「预包装食品」和主题「早餐必备」，两者谁也代替不了谁 ——
-- 类目回答「这是什么货、要什么资质」，主题回答「这周首页摆什么」。
--
-- **不与 mkt_campaign 合并**：运营想做「早餐必备」时往往只是「把这 20 件摆到一起」，
-- 并不想降价。合并会把「陈列」与「打折」绑死，而这两件事的决策人与决策周期都不同 ——
-- 结果是运营为了摆个专题被迫建一个 0 折扣的活动，而活动列表从此再也读不懂。

CREATE TABLE IF NOT EXISTS prd_topic
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    topic_no   VARCHAR(64)  NOT NULL COMMENT '主题业务键',
    title      VARCHAR(64)  NOT NULL COMMENT '主题名，C 端直接展示',
    subtitle   VARCHAR(128) DEFAULT NULL COMMENT '一句话说明，如「7 点前送到」',
    cover      VARCHAR(512) DEFAULT NULL COMMENT '封面图',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '首页排序，小的在前',
    -- 起止时间**都可空**：常设专题（「本地时令」）没有档期，
    -- 强制填一个假的结束时间会让它在某天悄悄消失，而没人记得自己填过
    start_at   DATETIME     DEFAULT NULL COMMENT '生效开始；空 = 立即生效',
    end_at     DATETIME     DEFAULT NULL COMMENT '生效结束；空 = 长期有效',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED（归档不删：C 端历史链接还指着它）',
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64)  DEFAULT NULL,
    updated_by VARCHAR(64)  DEFAULT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_no (topic_no),
    KEY idx_status_sort (status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='主题分类（陈列）。与类目正交，与活动分开：摆到一起 ≠ 降价';

CREATE TABLE IF NOT EXISTS prd_topic_goods
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    topic_no   VARCHAR(64) NOT NULL,
    goods_no   VARCHAR(64) NOT NULL,
    -- 冗余主体号：**运营端要按商家看「这家店被摆进了哪些专题」**，
    -- 没有它每次都要 join 回 prd_goods
    entity_no  VARCHAR(64) NOT NULL,
    sort       INT         NOT NULL DEFAULT 0 COMMENT '专题内排序，小的在前',
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一件商品在一个专题里只能有一行。并发靠它兜底：挑商品是勾选式界面，重复提交是常态
    UNIQUE KEY uk_topic_goods (topic_no, goods_no),
    KEY idx_goods (goods_no),
    KEY idx_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='主题 × 商品，多对多。与类目正交：一件豆浆既是预包装食品，也是早餐必备';

-- ── 种子：两个常设专题 ───────────────────────────────────────
--
-- 空表的后果不是「少点数据」，是**运营端页面与 C 端入口都看不出是没配还是坏了**。
-- 给两条常设的（没有档期），运营一进去就知道这页长什么样。
INSERT INTO prd_topic
(topic_no, title, subtitle, cover, sort, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('TP0001', '早餐必备', '7 点前送到楼下', '', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('TP0002', '本地时令', '当季当地，今天到货', '', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
