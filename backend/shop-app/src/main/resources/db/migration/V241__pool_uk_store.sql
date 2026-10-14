-- 社区池唯一键加门店维度（可见性按门店算 · 第 4 步）。
--
-- 第 3 步把建池口径从「主体可达 × 商品在架」改成「∃ 门店：这家店在架卖它 ∧ 这家店可达」，
-- 于是**一件货可以在同一个社区里出现多行**（两家店都摆着、都服务这个社区）。
-- 旧唯一键 uk_community_goods(community_no, goods_no) 会把第二家店的那一行挡在门外 ——
-- 表现是上架接口 500，而商家看到的是「系统开小差」。
--
-- ⚠ **这一版必须与第 3 步同批发布**。只发这一版：唯一键放宽了但没人写第二行，无害；
-- 只发第 3 步：第二家店的池行插不进去，多门店商家一上架就 500。
--
-- 存量数据不受影响：切口径前每个 (community_no, goods_no) 只有一行，
-- 加一维之后仍然唯一。
ALTER TABLE prd_community_pool DROP INDEX uk_community_goods;

-- store_no 可空（V240 的存量行回填不到默认店时留 NULL）。
-- MariaDB 的唯一索引里 NULL 互不相等 —— 也就是说 store_no 为空的行不受这个键约束。
-- 这是可以接受的：那些行是切口径之前写下的，第 3 步重建池时会被整批换掉。
CREATE UNIQUE INDEX uk_community_goods_store
    ON prd_community_pool (community_no, goods_no, store_no);
