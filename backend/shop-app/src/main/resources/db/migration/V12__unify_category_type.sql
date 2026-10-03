-- 统一五品类的取值：sys_channel_category_rule 用 GOODS，prd_goods 用 NORMAL。
--
-- 同一个「五品类」在后端有两套名字，而**商品筛选走的是 prd_goods.type**。
-- 后果实测过：C 端「日用百货」标签页按 GOODS 筛，库里 32 条商品全是 NORMAL，
-- 一条也筛不出来 —— 而页面写着「你的社区还没有这类商家」，
-- 把 bug 完美伪装成业务事实，只有真的点开那个标签页才会发现。
--
-- 以 prd_goods.type 为准（它是商品品类的权威字段），规则表跟着改。
UPDATE sys_channel_category_rule SET category_type = 'NORMAL' WHERE category_type = 'GOODS';
