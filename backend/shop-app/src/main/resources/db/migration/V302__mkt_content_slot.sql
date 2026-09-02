-- 内容位（首页楼层 / 轮播 / 频道）。**这一版只有 HOME_FLOOR 真的被消费。**
--
-- 为什么现在建：C 端首页那个「推荐商品」位今天展示的是 **销量事实，不是运营意图** ——
-- GoodsServiceImpl#promoted 按 sales 倒序兜底，注释里写着「一期无运营后台，按销量兜底……
-- 接上运营配置时只换这一段」。而页面上写的是「推荐」。运营端那一页（marketing/slots）
-- 也一直在 mock 上点得动：能开关、能归档，只是后端一行都没有。
--
-- BANNER 与 CHANNEL 这一版**不接**：C 端既没有轮播位也没有频道页
-- （c-app/src/pages/home/index.vue 里没有 swiper），没有承接位就定不了「跳去哪」这个模型，
-- 定了必返工。它们仍然能建、能排期 —— 只是没有任何端会去读，这是**明说的现状**不是漏洞。
--
-- ⚠️ 撞号风险：并行会话同一目录，本机 H2 测试不跑 Flyway，撞号只在下一次真库启动才暴露
-- （Found more than one migration with version 302），届时改号并 clean package。
CREATE TABLE IF NOT EXISTS mkt_content_slot
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    slot_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL COMMENT '运营自己认的名字，不出现在 C 端',
    kind VARCHAR(32) NOT NULL COMMENT 'HOME_FLOOR 首页楼层 / BANNER 轮播 / CHANNEL 频道。只有 HOME_FLOOR 有 C 端消费方',
    -- 列名避开 sort：MariaDB 与 H2 对它的保留字判定不一致，而两边不一致的东西
    -- 本机测试永远看不出来（H2 不跑 Flyway），只会在生产启动时炸
    sort_no INT(11) NOT NULL DEFAULT 0 COMMENT '同 kind 内的展示顺序，小的在前',
    community_nos TEXT DEFAULT NULL COMMENT 'JSON 数组；空 = 全部社区。与商品的社区池同一口径',
    goods_nos TEXT DEFAULT NULL COMMENT 'JSON 数组，**有序**——数组顺序就是首页楼层里的展示顺序',
    online_at BIGINT(20) NOT NULL COMMENT '毫秒。到点才展示',
    offline_at BIGINT(20) NOT NULL COMMENT '毫秒。过点即不展示；与 enabled 是两件事，enabled=关 立刻下，不等这个时间',
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    archived_at DATETIME DEFAULT NULL COMMENT '归档（软删语义一律 archive，见 architecture.md §10.6）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_slot_no (slot_no),
    KEY idx_content_slot_kind_sort (kind,sort_no)
) COMMENT='内容位：运营配的首页楼层/轮播/频道；这一版只有 HOME_FLOOR 被 C 端读';
