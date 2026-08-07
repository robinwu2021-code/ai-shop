-- P0 支付能力矩阵。设计见 docs/technical/多端多通道-详细设计.md。
--
-- 要解决的问题：平台要同时支持 3 种商家主体 × 2 个支付通道 × 5 个端 × 5 个品类。
-- 这些组合**不能铺成代码里的 if** —— 必然写错且改不动。做法是把「谁能在哪儿收钱」
-- 建成数据，判断收敛成三个纯函数。
--
-- 本版不依赖任何外部确认（服务商口径、Apple 规则）：待确认项影响的是
-- 规则表里的**数据**，不是表结构。

-- ---------------------------------------------------------------------------
-- 一、商家 × 通道的进件与能力
-- ---------------------------------------------------------------------------
--
-- 为什么 sub_mchid 不能是 usr_merchant 上的一列：商家可能同时进件微信与支付宝，
-- 两边的商户号、进件状态、授权能力各不相同，**甚至主体类型都可能不同**
-- （微信进小微、支付宝进个体户）。一列表达不了。

CREATE TABLE IF NOT EXISTS usr_merchant_payment
(
    id                    BIGINT       AUTO_INCREMENT PRIMARY KEY,
    merchant_no           VARCHAR(64)  NOT NULL,
    -- 留字符串不建枚举表：加一个通道是配置变更，不该是建模变更
    channel               VARCHAR(16)  NOT NULL COMMENT 'WECHAT/ALIPAY',

    -- **该通道下的**主体类型，可能与其它通道不同，所以在这张表而不是 usr_merchant 上。
    -- 它决定了：能用哪些支付方式、能否开票、税务归属、能否参与积分
    subject_type          VARCHAR(16)  NOT NULL DEFAULT 'MICRO' COMMENT 'MICRO/INDIVIDUAL/ENTERPRISE',

    sub_mchid             VARCHAR(64)   NULL COMMENT '二级商户号，进件成功后由通道回执回填',
    apply_no              VARCHAR(64)   NULL COMMENT '通道侧的进件申请单号',
    apply_status          VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/APPLYING/ACTIVE/REJECTED/FROZEN',
    reject_reason         VARCHAR(512)  NULL COMMENT '驳回原因，原样给商家看',

    -- **通道回执的实际授权**，不按主体类型推断。
    -- 推断会在规则变化时静默失效：以为个体户有 App 支付，而实际进件时
    -- 选的经营场景没勾 App —— 那时下单会在拉起支付时才失败。
    pay_methods           JSON          NULL COMMENT '["JSAPI","APP","H5","NATIVE"]，通道回执写入',

    -- 小微开不了票。用户下单时看不到「本店不能开票」，付完钱要发票时才发现，
    -- 是必然客诉 —— 所以它属于**下单前必须披露**的信息
    invoice_capable       TINYINT      NOT NULL DEFAULT 0 COMMENT '能否开票',

    -- 结算账户只存类型与脱敏串，真实账号由通道持有（ADR-002 §5：C/B 端都不回显）
    settle_account_type   VARCHAR(24)   NULL COMMENT 'PERSONAL_BANK/CORPORATE_BANK',
    settle_account_masked VARCHAR(64)   NULL,

    -- 手续费承担方可协商（微信协议允许）。按主体分档时用它落每商家的实际约定：
    -- 小微阶段平台承担降门槛，升个体户后转商家承担
    fee_bearer            VARCHAR(16)  NOT NULL DEFAULT 'MERCHANT' COMMENT 'MERCHANT/PLATFORM',

    applied_at            BIGINT        NULL,
    activated_at          BIGINT        NULL,
    tenant_no             VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at            DATETIME     NOT NULL,
    created_by            VARCHAR(64)   NULL,
    updated_at            DATETIME     NOT NULL,
    updated_by            VARCHAR(64)   NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    deleted               TINYINT      NOT NULL DEFAULT 0,

    -- 一个商家一个通道只能有一条 —— 靠库约束挡住重复进件
    UNIQUE KEY uk_mp_merchant_channel (merchant_no, channel),
    -- 分账回调只带 sub_mchid，要能反查到商家
    KEY idx_mp_sub_mchid (sub_mchid),
    -- 运营看「哪些进件卡住了」
    KEY idx_mp_status (apply_status, channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家支付进件（每通道一条）';

-- 约束名带 mp_ 前缀：MySQL 的索引名是**表级**唯一、H2 是 **schema 级**唯一。
-- 不带前缀迟早与别的表撞车 —— stl_split_log 已经踩过一次（V12 注释里有记录）。

-- ---------------------------------------------------------------------------
-- 二、端 × 品类 可售规则
-- ---------------------------------------------------------------------------
--
-- iOS 的 IAP 约束针对的是**商品性质**（数字内容 vs 实物/线下服务），
-- 所以规则主体是「端 × 品类」，不是给每个商品挂开关。
--
-- 为什么用表不用常量：Apple 的规则会变（2025 年美区外链裁定就是一例），
-- 改常量要发版、改表不用。**审核被拒时能当天调整**。

CREATE TABLE IF NOT EXISTS sys_channel_category_rule
(
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    -- 是「端」不是「支付通道」：同一个通道在不同端受的约束不同
    scene         VARCHAR(16)  NOT NULL COMMENT 'MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5',
    category_type VARCHAR(16)  NOT NULL COMMENT 'GOODS/FRESH/SERVICE/VIRTUAL/CARD',
    sellable      TINYINT      NOT NULL DEFAULT 1,
    -- 不可售原因：既给运营看，也直接作为端上的提示文案 —— 拒绝必须能说出为什么
    reason        VARCHAR(255)  NULL,
    updated_by    VARCHAR(64)   NULL,
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)   NULL,
    updated_at    DATETIME     NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ccr_scene_category (scene, category_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '端 × 品类 可售规则（iOS IAP 约束等）';

-- 初始数据：**全量 25 行**（5 端 × 5 品类），不是「只存例外」。
--
-- 缺行时的默认值是个陷阱：新增品类或新增端时，「查不到 = 可售」会静默放行，
-- 而那正是审核被拒的场景。全量存在，运营也能一眼看全。
INSERT INTO sys_channel_category_rule (scene, category_type, sellable, reason, created_at, updated_at, created_by)
VALUES
  ('MP_WECHAT','GOODS',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_WECHAT','FRESH',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_WECHAT','SERVICE',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_WECHAT','VIRTUAL',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_WECHAT','CARD',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_ALIPAY','GOODS',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_ALIPAY','FRESH',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_ALIPAY','SERVICE',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_ALIPAY','VIRTUAL',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('MP_ALIPAY','CARD',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('ANDROID','GOODS',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('ANDROID','FRESH',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('ANDROID','SERVICE',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('ANDROID','VIRTUAL',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('ANDROID','CARD',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('IOS','GOODS',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('IOS','FRESH',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('IOS','SERVICE',1,NULL,NOW(),NOW(),'SYSTEM'),
  -- Apple 规则：数字内容必须走 IAP（抽成 30%），远高于 0.6% 的支付费率，
  -- 该品类在 App 上无利可图。一期直接屏蔽，等 VIRTUAL 的准确定义确认后再放开。
  ('IOS','VIRTUAL',0,'iOS 平台规则限制，请在小程序端购买',NOW(),NOW(),'SYSTEM'),
  -- 卡券是线下核销消费，应属「App 外消费」不受 IAP 约束。**待法务确认后定稿**
  ('IOS','CARD',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('H5','GOODS',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('H5','FRESH',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('H5','SERVICE',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('H5','VIRTUAL',1,NULL,NOW(),NOW(),'SYSTEM'),
  ('H5','CARD',1,NULL,NOW(),NOW(),'SYSTEM');

-- ---------------------------------------------------------------------------
-- 三、增列
-- ---------------------------------------------------------------------------

-- 订单：下单端。对账要按端切分，排查「只有 iOS 出问题」这类情况也靠它。
-- pay_channel 已有（V3），不重复加。
ALTER TABLE ord_order ADD COLUMN pay_scene VARCHAR(16) NULL COMMENT '下单端 MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5';

-- 结算单：多通道 + **费率透传**。
--
-- 决策（总方案 §6.3）：按实际通道费率据实结算，账单标明每笔走哪个通道、扣了多少。
-- 曾考虑平台吸收通道差价、对商家用统一名义费率，理由是「商家控制不了用户选哪个通道」；
-- 改为透传是因为「无法解释」的前提是账单不告诉他 —— 标明来源后波动就可解释，
-- 且据实结算比平台补贴干净：争议时能下钻到单笔，指着通道说明。
ALTER TABLE stl_bill ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'WECHAT' COMMENT '分账实现按它路由，对账按它切分';
ALTER TABLE stl_bill ADD COLUMN pay_scene VARCHAR(16) NULL;
ALTER TABLE stl_bill ADD COLUMN channel_fee_minor BIGINT NOT NULL DEFAULT 0 COMMENT '该笔实际扣的通道手续费（分）';
-- 费率快照：与 commission_rate 同一个道理 —— 费率会变，历史账不能跟着变
ALTER TABLE stl_bill ADD COLUMN channel_fee_rate INT NOT NULL DEFAULT 0 COMMENT '通道费率快照（万分比）';
-- 优惠费率是申请来的、有有效期。到期回落时商家会发现费率变了，那时要能答出「哪天起、因为什么」
ALTER TABLE stl_bill ADD COLUMN channel_fee_source VARCHAR(16) NULL COMMENT 'STANDARD/PROMO';
ALTER TABLE stl_bill ADD COLUMN fee_bearer VARCHAR(16) NOT NULL DEFAULT 'MERCHANT';

-- 商品：端级可售例外。默认空 = 按品类规则走，只在个别商品需要破例时填。
ALTER TABLE prd_goods ADD COLUMN sellable_override JSON NULL COMMENT '端级可售例外 {"IOS":false}；空则按 sys_channel_category_rule';
