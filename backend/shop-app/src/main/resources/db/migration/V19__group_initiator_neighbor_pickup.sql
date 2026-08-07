-- 团购：补发起人，并建邻里自提点表（ADR-005）。
--
-- 背景：契约的 GroupBuy 有 initiatorNickname / isOwner / neighborPickup 三个字段，
-- 而库里既没有「谁发起的」，也没有承载邻里自提点的表 —— 于是这三个字段无从产出，
-- 依赖它们的三条接口（本团订单 / 批次签收 / 发起人核销）也就实现不了。
--
-- ⚠️ ful_group_pickup 此前**已在 DataScopeRegistration 里注册**，但表从未建立。
-- 注册一张不存在的表不会报错（注册表只是个 Map），所以这个缺口一直没人发现。

-- ── 发起人 ──────────────────────────────────────────────
-- 团分两种来源：商家开的团（发起人为空）与 C 端用户自发的团（ADR-004 后者才是主线）。
-- 用可空而不是给个默认值：为空**就是**「这是商家团」，不是「数据没填」。
ALTER TABLE mkt_group_buy
    ADD COLUMN initiator_user_no VARCHAR(64) NULL
        COMMENT 'C 端发起人；为空表示商家开的团。决定 isOwner 与「我发起的团」';

-- 「我发起的团」是发起人自己的高频入口（要看待取订单、要核销），单独走索引
CREATE INDEX idx_group_initiator ON mkt_group_buy (initiator_user_no);


-- ── 邻里自提点（ADR-005 / C-GB-06）────────────────────────
--
-- 发起人开团时勾「送到我家」，就在这里建一个**团粒度的临时自提点**：随团生、随团灭。
--
-- 为什么不复用 cmt_pickup_point：那张表是常驻网点的主数据（有营业时间、有服务费口径、
-- 运营要维护）。团粒度的点只活到这一团核销完，混进主数据会让「本社区有哪些自提点」
-- 这个查询每次都要额外排除一堆已经消失的临时点。
--
-- **零报酬是硬约束**：这张表刻意**不设任何费用列**。有费用列就迟早有人填，
-- 而一旦承接的邻居能收钱，他就是团长 —— ADR-004 消掉的合规问题会原样回来。
CREATE TABLE IF NOT EXISTS ful_group_pickup
(
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    pickup_no    VARCHAR(64)  NOT NULL,
    group_no     VARCHAR(64)  NOT NULL COMMENT '所属团。作用域就是它 —— 拿别团的码来核销必须被拒',
    user_no      VARCHAR(64)  NOT NULL COMMENT '承接人 = 团发起人本人，不能是别人',
    name         VARCHAR(128) NOT NULL COMMENT '如「3 幢老王家」',
    -- 成团前只到楼栋，付款后才给完整门牌（B13）—— 未成团的团不该暴露发起人住址
    address      VARCHAR(255) NOT NULL COMMENT '完整地址；对未付款用户按 B13 脱敏后下发',
    time_slot    VARCHAR(64)   NULL COMMENT '约定取货时段：邻居家不能一直堆着货（B15）',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED（随团结束）',
    -- 批次签收：发起人确认「这一车货我收到了」，之后才谈得上逐单核销
    received_at  BIGINT        NULL COMMENT '批次签收时间；未签收前不允许核销',
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(64)   NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)   NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_group_pickup_no (pickup_no),
    -- 一团一点：勾了「送到我家」就只有这一个点，重复建点会让核销作用域失去意义
    UNIQUE KEY uk_group (group_no),
    KEY idx_user (user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '邻里自提点（团粒度临时点，随团生灭，零报酬）';


-- ── 商品的团购配置 ──────────────────────────────────────
--
-- 验收清单里有一条拒绝规则：「该商品未开放拼团」（createGroupBuy）。
-- 但库里**没有任何字段能表达「这个商品开没开团购」** —— 规则无从实现，
-- 于是要么放行任意商品开团（用户自己定不了价，会开出一个没有团购价的团），
-- 要么在代码里写死一个白名单。两条都不对。
--
-- 团购价存在商品上而不是让开团人填：**开团的是用户，定价的必须是商家**。
-- 用户只是把商家已经配好的团「开出来」。
ALTER TABLE prd_goods
    ADD COLUMN group_price_minor BIGINT NULL
        COMMENT '团购价（分）。为 NULL 即「未开放拼团」，C 端开团直接拒';

ALTER TABLE prd_goods
    ADD COLUMN group_min_count INT NULL
        COMMENT '起团人数；未配时按 2 人起';
