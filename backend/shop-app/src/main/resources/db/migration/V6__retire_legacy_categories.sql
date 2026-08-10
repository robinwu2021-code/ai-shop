-- 清掉旧编号的类目树（CAT-ROOT-1 / CAT001…），统一到 V4 那一套。
--
-- 背景：这批节点是 V4 之前 DevSeeder 灌的演示数据，**是第三套编号** ——
-- 真库一棵树、H2 一棵树、前端 mock 又一棵树。DevSeeder 已经改成灌 V4 那套，
-- 但已经跑过的开发库里两套并存：`/mp/category/tree` 会返回两棵树，
-- 商家在选择器里看到「日用百货」出现两次，而两个都点得进去。
--
-- 这个迁移只动**演示数据**：先把引用改指到对应的新节点，再删旧节点。
-- 先改后删的顺序不能反 —— 反了会留下一批 category_no 指向已删节点的商品，
-- 它们既不为空、又不属于任何类目，类目筛选和资质校验会一起漏掉它们。

-- 米面粮油 / 生鲜果蔬 → 纸品清洁（无资质门槛，演示商品不该卡在准入上）
UPDATE prd_goods SET category_no = 'CAT210' WHERE category_no IN ('CAT001', 'CAT002');
-- 家政保洁 → 生活服务
UPDATE prd_goods SET category_no = 'CAT300' WHERE category_no = 'CAT003';

DELETE FROM prd_category
WHERE category_no IN ('CAT-ROOT-1', 'CAT-ROOT-2', 'CAT001', 'CAT002', 'CAT003');
