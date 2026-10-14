-- 楼栋归属 + 经营范围的正负。二批（楼栋与精确覆盖）的结构底子。
--
-- ① parent_no：楼栋挂在小区/园区下。**只做两层** ——
--    园区 › 楼 › 单元 › 户会没完没了，而单元和户不是服务单位：
--    没有商家按单元框范围，它们属于收货地址的门牌号（house_no，V319）。
--    小区/村/园区的 parent 为空，它们直接挂 region_code。
--
--    归属是**声明的**，不靠围栏几何推断：大小区边上的一栋楼可能落在小区中心圆之外，
--    而隔壁小区的圆反而把它罩进去。围栏只回答「我此刻站在哪一个聚落里」。
--
-- ② mode：INCLUDE 纳入 / EXCLUDE 排除。默认 INCLUDE，**存量行行为逐字不变**。
--    它回答的是「商家框了小区，算不算覆盖里面每栋楼」——默认算，但给一个显式的出口。
--
-- ⚠️ 这条迁移**必须与 reachableCommunities 的改造同批上线**，且上线后要
--    全量重建 prd_community_pool：存量池行是按「没有 EXCLUDE、没有楼栋」的口径
--    写下来的，不重建的话排除一条也不生效，而 B 端会显示「已排除」——
--    说的和做的对不上，且没有任何报错。
ALTER TABLE cmt_community ADD COLUMN parent_no VARCHAR(64) NULL COMMENT '所属聚落（楼栋→小区/园区）。为空=顶层聚落，直接挂 region_code';

CREATE INDEX idx_cmt_community_parent ON cmt_community (parent_no);

ALTER TABLE mch_service_area ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'INCLUDE' COMMENT '覆盖方向：INCLUDE 纳入 / EXCLUDE 排除。展开时先并后减，EXCLUDE 优先';
