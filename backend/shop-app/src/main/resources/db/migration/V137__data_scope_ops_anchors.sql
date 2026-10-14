-- 运营端数据域接入 · 批① 的锚点列（TDD-运营端数据域接入 §2.4）。
--
-- `DataScopeHandler` 对**已注册的表是 fail-closed**：当前会话的维度在该表锚点里
-- 一个都找不到时，它拼的是 `1=0` 而不是放行。运营会话的维度是
-- MERCHANT / COMMUNITY / PICKUP，而 `ord_sub_order` 上只有 entity_no 与 pickup_no ——
-- 社区在**主单**上，子单没有。
--
-- 于是「把 ops 订单查询接上数据域」这一步，如果不先补这一列，
-- 结果是**配了社区域的运营打开订单页整页空白，且不报错** ——
-- 而空白看起来像「这个片区今天没单」，不像故障。
-- 这正是类注释里那条教训的同一个形状（订单表登记了 MERCHANT 却漏了 SELF，
-- C 端「我的订单」立刻空列表）。
--
-- 为什么是冗余列而不是 join 主单：数据域是在 **SQL 层由拦截器追加 where** 的，
-- 它只认「本表上的一列」。join 出来的列它够不着。
ALTER TABLE ord_sub_order
    ADD COLUMN community_no VARCHAR(64) DEFAULT NULL COMMENT '下单时买家所属社区，冗余自 ord_order —— 数据域 COMMUNITY 维度的锚点',
    ADD KEY idx_sub_order_community (community_no, id);

-- 回填存量。空 = 不限定（Q3），所以主单上本来就没有社区的那些单回填后仍是 NULL；
-- 它们对配了社区域的运营不可见 —— 这是对的：没有社区的单不属于任何片区。
UPDATE ord_sub_order s
    JOIN ord_order o ON o.order_no = s.order_no
SET s.community_no = o.community_no
WHERE s.community_no IS NULL;
