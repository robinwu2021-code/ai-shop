-- 商品提交历史（TDD-商品编辑页-录入落点与发布历史 §5.1 · AC11–AC13）。
--
-- ## 为什么要一张新表
--
-- prd_goods_draft **只存当前一份**草稿（goods_no 唯一），发布就把行删掉。
-- 于是发布不留痕：发完只剩「线上是什么」，查不到「发过什么」——
-- 谁在什么时候改了哪几项、上一版长什么样、为什么被驳回，一概无从回答。
-- 发布冲突时「线上在你保存之后有过变动」已经能说出来，却说不出**是谁改的**。
--
-- ## 形状
--
-- 一次保存一行。payload 与草稿表同形（整份 SaveCommand 的 JSON 快照）——
-- 快照不是契约，形状随编辑器走；它的用处是「取回这一版」时回放一次保存。
--
-- ⚠️ 版本号列叫 revision_no 不叫 version：version 是 BaseEntity 的乐观锁列，
-- 同名会被 MyBatis-Plus 的 @Version 接走，每次 UPDATE 自增 —— 那就不是版本号了。
--
-- change_summary 存「改了哪几项」的字段标签，发布预览算完顺手落下来。
-- 不存结构化 diff：diff 的两端是两份 payload，随时能重算；而标签是给人看的摘要，
-- 重算要依赖当时的 i18n 与类目文案，那些会变。
--
-- entry_source 回答「这一版怎么录的」—— 手填 / 快速录入 / 压缩包 / 图片识别。
-- 这是识别与历史的接缝：三个月后问「这批参数哪来的」，答案在这一列。
--
-- 没有 saved_by / saved_at 两列：BaseEntity 的 created_by / created_at 就是它们。
-- published_by / published_at 另立，因为保存与发布是两个时刻、常常是两个人。
CREATE TABLE prd_goods_revision
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    goods_no VARCHAR(64) NOT NULL COMMENT '商品号',
    entity_no VARCHAR(64) NOT NULL COMMENT '经营主体号。带域表，查询要走域',
    revision_no INT NOT NULL COMMENT '版本号：同一商品内自增。不叫 version——那是乐观锁列',
    base_revision INT DEFAULT NULL COMMENT '这一版基于哪一版（上一个 ONLINE 的 revision_no）',
    payload TEXT NOT NULL COMMENT '整份 SaveCommand 的 JSON 快照。与草稿表同形，非契约',
    change_summary VARCHAR(500) DEFAULT NULL COMMENT '改了哪几项：字段标签顿号连接。给人看的摘要，不是结构化 diff',
    entry_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 手填 / QUICK_TEXT 快速录入 / ZIP 压缩包 / IMAGE 图片识别',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 未发布 / ONLINE 线上在售 / SUPERSEDED 已被替换 / REJECTED 已驳回',
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因。审核驳回时落',
    published_by VARCHAR(64) DEFAULT NULL COMMENT '发布人。与 created_by 常常不是同一个人',
    published_at DATETIME DEFAULT NULL COMMENT '发布时间',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_goods_revision UNIQUE (goods_no, revision_no),
    KEY idx_revision_goods (goods_no, id),
    KEY idx_revision_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci COMMENT='商品提交历史：一次保存一行快照。发布不留痕是此前查不到「发过什么」的根因';
