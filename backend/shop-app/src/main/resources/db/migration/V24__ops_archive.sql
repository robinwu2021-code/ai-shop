-- 运营端归档：四个实体的软删除标记。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么需要
-- ─────────────────────────────────────────────────────────────────────────────
-- ops-web 每个列表页都有「归档」按钮，而后端只实现了 categories 一个域。
-- 实测（docs/technical/运营端死按钮实测清单.md）：券/商家/自提点/活动的归档按钮
-- **点得到、抄了编号、确认之后 404**，页面报「资源不存在」——
-- 而运营要归档的那张券就在列表里明明白白。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么是独立一列，而不是像 categories 那样复用 status
-- ─────────────────────────────────────────────────────────────────────────────
-- categories 的 status 只有 ACTIVE/ARCHIVED 两态，复用没有歧义。
-- 这四个不一样，它们的 status 有**正在使用中的业务含义**：
--   mkt_coupon    ACTIVE / PAUSED / ENDED   —— 还能不能领
--   mkt_campaign  RUNNING / PAUSED / ENDED  —— 还生不生效
--   mch_entity    ACTIVE / 封禁 …           —— 还能不能经营
-- **归档与暂停是两件事**：暂停的券仍在列表里等着被恢复，归档的券从默认列表消失。
-- 挤进同一列的话，「暂停后归档」这个再正常不过的操作会丢掉其中一个状态。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么是时间而不是布尔
-- ─────────────────────────────────────────────────────────────────────────────
-- ops-web 的 Archivable 契约就是 `archivedAt`，且「什么时候归的档」本身要展示。
-- 布尔存不下这个信息，而事后想补是补不出来的。
--
-- 不建索引：这四张表都是几百到几千行量级的主数据，全表扫比维护索引便宜；
-- 真到了需要索引的量级，那时的查询形态也不会是今天这个。
ALTER TABLE mkt_coupon
    ADD COLUMN IF NOT EXISTS archived_at DATETIME DEFAULT NULL
        COMMENT '归档时间。软删除标记，有值即从默认列表消失。与 status 正交';

ALTER TABLE mch_entity
    ADD COLUMN IF NOT EXISTS archived_at DATETIME DEFAULT NULL
        COMMENT '归档时间。软删除标记，有值即从默认列表消失。与 status 正交';

ALTER TABLE cmt_pickup_point
    ADD COLUMN IF NOT EXISTS archived_at DATETIME DEFAULT NULL
        COMMENT '归档时间。软删除标记，有值即从默认列表消失。与 status 正交';

ALTER TABLE mkt_campaign
    ADD COLUMN IF NOT EXISTS archived_at DATETIME DEFAULT NULL
        COMMENT '归档时间。软删除标记，有值即从默认列表消失。与 status 正交';
