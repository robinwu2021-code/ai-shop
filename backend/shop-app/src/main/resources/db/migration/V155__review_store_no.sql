-- 评价归门店（ADR-011 决定表第 3 行，TDD-评价归门店）。
--
-- ADR-011 早就定了「评价归门店：顾客评的是楼下那家，三家店评分混成一个，
-- 好店会被差店拖下去」，而 rvw_review 上一直只有 entity_no。
-- 后果不是少一个字段，是**已经交付的跨店对比对不上需求** ——
-- 那一页现在只能在顶部放一个主体级评分，还要专门写一句「为什么三家店是同一个数」。

ALTER TABLE rvw_review
    ADD COLUMN store_no VARCHAR(32) DEFAULT NULL COMMENT '评价归属门店（下单那一刻子单上的 store_no）；空 = 老评价，只计主体分';

-- 按 (store_no, status) 建索引：门店评分重算就是按这两列扫一遍
CREATE INDEX idx_rvw_store ON rvw_review (store_no, status);

-- 门店的评分派生列。**与 mch_entity 上那几列同名同口径**（都 ×10 存整数）——
-- 两套口径的后果是「主体 4.6 分，三家店分别是 4.8/4.7/4.9」，
-- 而没有人能解释那个 4.6 是怎么来的。
ALTER TABLE mch_store
    ADD COLUMN rating INT(11) NOT NULL DEFAULT 0 COMMENT '门店综合评分 ×10',
    ADD COLUMN rating_count INT(11) NOT NULL DEFAULT 0 COMMENT '计入门店评分的评价条数（只算审核通过的）',
    ADD COLUMN score_goods INT(11) NOT NULL DEFAULT 0 COMMENT '商品维度 ×10',
    ADD COLUMN score_service INT(11) NOT NULL DEFAULT 0 COMMENT '服务维度 ×10',
    ADD COLUMN score_speed INT(11) NOT NULL DEFAULT 0 COMMENT '履约速度维度 ×10';

-- 回填：能从子单反查到门店的，把当时就存在的事实补进来。
--
-- **不给反查不到的评价编一个默认店**（TDD §2.1）：硬塞给默认店会让那家店的分
-- 凭空多出一批来路不明的评价，而那批评价的顾客从来没去过那家店。
-- 空就是空 —— 它照常计入主体评分，只是不计入任何一家门店。
--
-- ⚠️ 这条是 UPDATE … JOIN（MySQL/MariaDB 方言）。测试 schema 生成器会**跳过**它
-- （H2 的 UPDATE 不接 JOIN，且测试库本来就是空的，搬无可搬）——
-- 所以 H2 上验不到这一步，它只在真库生效。
UPDATE rvw_review r
    JOIN ord_sub_order s ON s.sub_order_no = r.sub_order_no
SET r.store_no = s.store_no
WHERE r.store_no IS NULL
  AND s.store_no IS NOT NULL
  AND s.store_no <> '';

-- 回填门店评分：**不在这里算**。
--
-- 评分口径带 180 天时间加权（RATING_HALF_LIFE_DAYS），SQL 里重写一遍等于把
-- 那个口径抄成两份，而两份迟早分岔 —— 分岔的表现是「后台显示 4.7、重算一次变 4.5」。
-- 门店评分由下一次评价发表/审核时的 recomputeRating 自然填上；
-- 在那之前门店分是 0，页面按 rating_count = 0 显示「暂无评价」，与新店同一个形状。
