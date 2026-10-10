-- 商品双版本：草稿表（工单-商品双版本发布 步骤 1；TDD-商品规格与发布 §3.3）。
--
-- ## 为什么
--
-- V247 的「保存即自动下架送审」是单版本模型下的最优解，代价是**审核期间线上是空的**。
-- 双版本让一件商品最多两份：线上版（prd_goods，C 端只读它，照常卖）+
-- 草稿版（这张表，B 端编辑缓冲）。发布 = 一个事务里烘焙草稿 → 覆写线上 → 删草稿行。
--
-- ## 形状
--
-- goods_no 唯一 —— **一件商品至多一份未发布修改**。「有草稿」标识的判据就是
-- 「这张表有没有行」：保存的内容与线上相同时删行，防假标识。
--
-- payload 是整份 SaveCommand 的 JSON。**编辑缓冲，不是契约** —— 形状随编辑器走，
-- 正确性由发布时的编译点（bakeForPublish）保证，不由存储形状保证。
--
-- base_version 记草稿基于哪一版线上（prd_goods.version，乐观锁列）：发布时对不上
-- = 中途有人改过线上（多端同时编辑、运营强改），拒并引导先看差异，不静默覆盖。
-- ⚠️ 不用 updated_at 当基版：DATETIME 是秒级精度，同一秒内的改动检测不到 ——
-- 三个测试类连跑时就撞出来了（force-off 与建草稿落在同一秒，冲突静默漏过）。
-- version 每次 UPDATE 必增一，无精度问题。
--
-- ⚠️ 发布/丢弃走**物理删除**（mapper 的 purge）：唯一键不含 deleted，
-- 逻辑删会挡住同一商品再建草稿 —— V195 覆盖表踩过的同一个坑。
CREATE TABLE prd_goods_draft
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    goods_no VARCHAR(64) NOT NULL COMMENT '一件商品至多一份草稿',
    entity_no VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL COMMENT '整份 SaveCommand 的 JSON。编辑缓冲非契约，正确性由发布编译点保证',
    base_version BIGINT DEFAULT NULL COMMENT '草稿基于哪一版线上（prd_goods.version）。发布时对不上=有人中途改过线上，拒。不用 updated_at：秒级精度会漏同秒改动',
    status VARCHAR(16) NOT NULL DEFAULT 'EDITING' COMMENT 'EDITING 编辑中 / SUBMITTED 已提交待审（审核开关开着时）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_goods_draft UNIQUE (goods_no),
    KEY idx_draft_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci
  COMMENT='商品草稿：线上照卖旧版，编辑落这里。发布=事务换版+物理删行';
