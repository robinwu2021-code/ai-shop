-- C 端列表的两条热路径终于有索引了（工单-规格联动与查询性能一期 步骤 3）。
--
-- 此前 prd_goods 只有 entity_no / type / title(32) 三条二级索引 ——
-- 列表页过滤用的四个列一个都没有，排序列 sales 也没有。生产 EXPLAIN：
-- type=ALL + Using filesort，而它是首页/分类页每一次翻页都要跑的那条。
-- 380 行时免费，38 万行时是全表扫 + 外部排序。
--
-- deleted 放最前：@TableLogic 让每条查询都自带 deleted=0。
--
-- **两条不能合一**：不带 category_no 的查询（首页/全站）在第一条索引里
-- 跳过了中间列，sales 就不能用于索引排序 —— 优化器不是「用一半再 filesort」，
-- 是整条索引都放弃（生产上拿 idx_stl_pool_channel 实测过，见
-- docs/technical/design/商品规格参数-模型与查询性能.md §6.1）。
CREATE INDEX idx_goods_cat_sales ON prd_goods (deleted, on_sale, audit_status, category_no, sales);
CREATE INDEX idx_goods_sales     ON prd_goods (deleted, on_sale, audit_status, sales);
