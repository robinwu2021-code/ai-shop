-- 增值包与门店额度（P-11.2，TDD-增值包与门店额度）。
--
-- 在此之前，「这个商家能开几家店」是一个**全局配置** `shop.store.max-per-entity`（默认 1）——
-- 对所有商家同一个数，运营调一次影响所有人。本迁移把它换成按主体的订阅档位。
--
-- **上线当天零行为变化**：存量主体全部回填 FREE / 门店额度 1 / 子账号额度 0，
-- 与今天那个配置的默认值逐字一致。这一条是整批改动能安全发的全部理由。

-- ── 档位定义：平台配置，运营可调不发版（与 sys_industry / sys_pay_channel 同构，ADR-010）──
CREATE TABLE IF NOT EXISTS sys_merchant_plan_def (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    plan_code         VARCHAR(16)  NOT NULL COMMENT 'FREE / PRO / CHAIN',
    name              VARCHAR(64)  NOT NULL,
    store_quota       INT          NOT NULL DEFAULT 1 COMMENT '门店额度',
    staff_quota       INT          NOT NULL DEFAULT 0 COMMENT '子账号额度',
    -- 能力位。一期只有这一个 —— 它就是这个包卖的东西本身（B-11.12.5/6 跨店总览与对比）
    cross_store_stats TINYINT      NOT NULL DEFAULT 0 COMMENT '1=有跨店总览与对比',
    trial_days        INT          NOT NULL DEFAULT 0 COMMENT '试用天数，0=不可试用',
    -- 停售某一档：只影响**新订阅**，已订阅的人照常用到到期
    enabled           TINYINT      NOT NULL DEFAULT 1,
    sort              INT          NOT NULL DEFAULT 0,
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)           DEFAULT NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)           DEFAULT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_code (plan_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='增值包档位定义';

-- ── 主体的订阅实例：一主体一行 ──
CREATE TABLE IF NOT EXISTS mch_entity_plan (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    entity_no      VARCHAR(64)  NOT NULL,
    plan_code      VARCHAR(16)  NOT NULL DEFAULT 'FREE',
    -- 额度**快照**，不是每次查档位定义。
    -- 运营下调 PRO 的门店额度时，已经开了 3 家店的存量商家不该突然有一家变只读 ——
    -- 快照让「改档位定义」只影响之后新订阅的人。这是老用户保护，不是冗余。
    store_quota    INT          NOT NULL DEFAULT 1,
    staff_quota    INT          NOT NULL DEFAULT 0,
    cross_store_stats TINYINT   NOT NULL DEFAULT 0,
    -- 单商家额度覆盖（P-11.2.4）：谈下来的连锁客户不走自助付费。
    -- NULL = 用快照。**不写进 store_quota** —— 混在一起就分不清「这个数是档位给的还是单独谈的」
    store_quota_override INT             DEFAULT NULL,
    staff_quota_override INT             DEFAULT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / GRACE / EXPIRED',
    start_at       BIGINT                DEFAULT NULL,
    expire_at      BIGINT                DEFAULT NULL COMMENT 'FREE 无到期',
    -- 催收要靠它区分「自助付费的该催」与「平台赠送的别催」；一期没有付款单，推断不出来
    granted_by     VARCHAR(16)  NOT NULL DEFAULT 'SELF_PAID' COMMENT 'SELF_PAID / PLATFORM / TRIAL',
    trial_used     TINYINT      NOT NULL DEFAULT 0 COMMENT '一主体一次，置 1 后永不回退',
    -- 降级执行到哪一步。重跑到期任务时靠它幂等，不重复把门店压成只读
    downgraded_at  BIGINT                DEFAULT NULL,
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)           DEFAULT NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)           DEFAULT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity_plan (entity_no),
    KEY idx_plan_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主体的增值包订阅';

-- ── 门店：区分「降级压下的」与「商家自己停用的」──
--
-- 状态仍复用 READONLY（不新增状态值），但只看 status 是分不出来的：
-- 降级压了 2 家、商家又自己停了 1 家，三家状态一模一样 ——
-- 而补缴恢复时，**全恢复 = 平台替商家做了开店决定，全不恢复 = 他买的东西没还给他**。
--
-- 与 prd_store_goods.platform_suspended（V98）是同一个形状：
-- 凡是「系统压下去、以后要按原样还回来」的地方，都必须留标记说明是谁压的。
ALTER TABLE mch_store
    ADD COLUMN plan_suspended TINYINT NOT NULL DEFAULT 0 COMMENT '1=因套餐降级被压为只读，补缴时恢复并清零';

-- ── 三档种子 ──
INSERT INTO sys_merchant_plan_def
    (plan_code, name, store_quota, staff_quota, cross_store_stats, trial_days, enabled, sort,
     tenant_no, created_at, updated_at, version, deleted)
SELECT 'FREE', '孵化版', 1, 0, 0, 0, 1, 10, 'MAIN', NOW(), NOW(), 0, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def x WHERE x.plan_code = 'FREE');

INSERT INTO sys_merchant_plan_def
    (plan_code, name, store_quota, staff_quota, cross_store_stats, trial_days, enabled, sort,
     tenant_no, created_at, updated_at, version, deleted)
SELECT 'PRO', '成长版', 3, 3, 1, 14, 1, 20, 'MAIN', NOW(), NOW(), 0, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def x WHERE x.plan_code = 'PRO');

INSERT INTO sys_merchant_plan_def
    (plan_code, name, store_quota, staff_quota, cross_store_stats, trial_days, enabled, sort,
     tenant_no, created_at, updated_at, version, deleted)
SELECT 'CHAIN', '连锁版', 10, 15, 1, 14, 1, 30, 'MAIN', NOW(), NOW(), 0, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def x WHERE x.plan_code = 'CHAIN');

-- ── 存量主体回填 FREE ──
--
-- ⚠️ 这是 DML，**测试库只重放 DDL 不跑 DML**（gen-test-schema.py 的既有行为）——
-- 也就是说这一条在 CI 里从来不会被执行。**上生产前必须在预发库单独验一次**。
-- 本轮已在数据域批① 的 V137 上踩过同一个坑。
--
-- 回填成 1/0 而不是读配置：配置是可改的，而这里要钉死的是「与上线那一刻的行为一致」。
INSERT INTO mch_entity_plan
    (entity_no, plan_code, store_quota, staff_quota, cross_store_stats,
     status, granted_by, trial_used, tenant_no, created_at, updated_at, version, deleted)
SELECT m.entity_no, 'FREE', 1, 0, 0, 'ACTIVE', 'SELF_PAID', 0, 'MAIN', NOW(), NOW(), 0, 0
  FROM mch_entity m
 WHERE m.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM mch_entity_plan p WHERE p.entity_no = m.entity_no);
